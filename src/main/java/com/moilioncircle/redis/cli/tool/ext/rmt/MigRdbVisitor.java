package com.moilioncircle.redis.cli.tool.ext.rmt;

import cn.nextop.lite.pool.Pool;
import cn.nextop.lite.pool.glossary.Lifecyclet;
import com.moilioncircle.redis.cli.tool.cmd.glossary.Type;
import com.moilioncircle.redis.cli.tool.conf.Configure;
import com.moilioncircle.redis.cli.tool.ext.AbstractRdbVisitor;
import com.moilioncircle.redis.cli.tool.ext.AsyncEventListener;
import com.moilioncircle.redis.cli.tool.ext.GuardRawByteListener;
import com.moilioncircle.redis.cli.tool.util.pooling.ClientPool;
import com.moilioncircle.redis.replicator.Configuration;
import com.moilioncircle.redis.replicator.RedisURI;
import com.moilioncircle.redis.replicator.Replicator;
import com.moilioncircle.redis.replicator.cmd.impl.DefaultCommand;
import com.moilioncircle.redis.replicator.event.Event;
import com.moilioncircle.redis.replicator.event.EventListener;
import com.moilioncircle.redis.replicator.event.PostFullSyncEvent;
import com.moilioncircle.redis.replicator.event.PreFullSyncEvent;
import com.moilioncircle.redis.replicator.io.RedisInputStream;
import com.moilioncircle.redis.replicator.rdb.datatype.DB;
import com.moilioncircle.redis.replicator.rdb.datatype.Module;
import com.moilioncircle.redis.replicator.rdb.dump.datatype.DumpKeyValuePair;
import com.moilioncircle.redis.replicator.rdb.module.ModuleParser;
import com.moilioncircle.redis.replicator.rdb.skip.SkipRdbParser;
import com.moilioncircle.redis.replicator.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static com.moilioncircle.redis.replicator.Constants.MODULE_SET;
import static redis.clients.jedis.Protocol.Command.SELECT;
import static redis.clients.jedis.Protocol.toByteArray;

/**
 * @author Baoyi Chen
 */
public class MigRdbVisitor extends AbstractRdbVisitor implements EventListener {
    
    private static final Logger logger = LoggerFactory.getLogger(MigRdbVisitor.class);
    
    private final RedisURI uri;
    private final boolean replace;
    private final Pool<ClientPool.Client> pool;
    private final AtomicInteger dbnum = new AtomicInteger(-1);
    
    public MigRdbVisitor(Replicator replicator,
                         Configure configure,
                         String uri,
                         List<Long> db,
                         List<String> regexs,
                         List<Type> types,
                         boolean replace) throws Exception {
        super(replicator, configure, db, regexs, types);
        this.replace = replace;
        this.uri = new RedisURI(uri);
        Configuration config = Configuration.valueOf(this.uri);
        
        int timeout = configure.getTimeout();
        String password = config.getAuthPassword();
        this.pool = ClientPool.create(this.uri.getHost(), this.uri.getPort(), password, timeout);
        this.replicator.addEventListener(new AsyncEventListener(this, replicator, configure));
        this.replicator.addCloseListener(e -> Lifecyclet.stopQuietly(this.pool));
    }
    
    @Override
    public void onEvent(Replicator replicator, Event event) {
        if (event instanceof PreFullSyncEvent) {
            dbnum.set(-1);
            return;
        }
        if (event instanceof PostFullSyncEvent) {
            return;
        }
        retry(event, configure.getMigrateRetryTime());
    }
    
    public void retry(Event event, int times) {
        ClientPool.Client target = null;
        try {
            target = pool.acquire();
            if (target == null) {
                throw new TimeoutException("redis pool acquire timeout");
            }
            if (event instanceof DumpKeyValuePair) {
                DumpKeyValuePair dkv = (DumpKeyValuePair) event;
                // Step1: select db
                DB db = dkv.getDb();
                int index;
                if (db != null && (index = (int) db.getDbNumber()) != dbnum.get()) {
                    String r = target.send(SELECT, toByteArray(index));
                    if (r != null) logger.error("select {} failed. reason:{}", index, r);
                    dbnum.set(index);
                }
    
                // Step2: restore dump data
                if (dkv.getExpiredMs() == null) {
                    String r = target.restore(dkv.getKey(), 0L, dkv.getValue(), replace);
                    if (r != null) logger.error("restore {} failed. reason:{}", Strings.toString(dkv.getKey()), r);
                } else {
                    long ms = dkv.getExpiredMs() - System.currentTimeMillis();
                    if (ms <= 0) return;
                    String r = target.restore(dkv.getKey(), ms, dkv.getValue(), replace);
                    if (r != null) logger.error("restore {} failed. reason:{}", Strings.toString(dkv.getKey()), r);
                }
            } else if (event instanceof DefaultCommand) {
                // Step3: sync aof command
                DefaultCommand dc = (DefaultCommand) event;
                String r = target.send(dc.getCommand(), dc.getArgs());
                if (r != null) logger.error("{} failed. reason:{}", Strings.toString(dc.getCommand()), r);
            }
        } catch (Throwable e) {
            if (target != null) {
                pool.release(target);
                target = null;
            }
            times--;
            if (times >= 0) retry(event, times);
            else logger.error("internal error occur. reason:{}", e.getMessage());
        } finally {
            if (target != null) pool.release(target);
        }
    }
    
    @Override
    protected Event doApplyString(RedisInputStream in, DB db, int version, byte[] key, boolean contains, int type) throws IOException {
        GuardRawByteListener listener = new GuardRawByteListener((byte) type, version, null);
        replicator.addRawByteListener(listener);
        new SkipRdbParser(in).rdbLoadEncodedStringObject();
        replicator.removeRawByteListener(listener);
        DumpKeyValuePair kv = new DumpKeyValuePair();
        kv.setDb(db);
        kv.setValueRdbType(type);
        kv.setKey(key);
        kv.setValue(listener.getBytes());
        return kv;
    }
    
    @Override
    protected Event doApplyList(RedisInputStream in, DB db, int version, byte[] key, boolean contains, int type) throws IOException {
        GuardRawByteListener listener = new GuardRawByteListener((byte) type, version, null);
        replicator.addRawByteListener(listener);
        SkipRdbParser skip = new SkipRdbParser(in);
        long len = skip.rdbLoadLen().len;
        while (len > 0) {
            skip.rdbLoadEncodedStringObject();
            len--;
        }
        replicator.removeRawByteListener(listener);
        DumpKeyValuePair kv = new DumpKeyValuePair();
        kv.setDb(db);
        kv.setValueRdbType(type);
        kv.setKey(key);
        kv.setValue(listener.getBytes());
        return kv;
    }
    
    @Override
    protected Event doApplySet(RedisInputStream in, DB db, int version, byte[] key, boolean contains, int type) throws IOException {
        GuardRawByteListener listener = new GuardRawByteListener((byte) type, version, null);
        replicator.addRawByteListener(listener);
        SkipRdbParser skip = new SkipRdbParser(in);
        long len = skip.rdbLoadLen().len;
        while (len > 0) {
            skip.rdbLoadEncodedStringObject();
            len--;
        }
        replicator.removeRawByteListener(listener);
        DumpKeyValuePair kv = new DumpKeyValuePair();
        kv.setDb(db);
        kv.setValueRdbType(type);
        kv.setKey(key);
        kv.setValue(listener.getBytes());
        return kv;
    }
    
    @Override
    protected Event doApplyZSet(RedisInputStream in, DB db, int version, byte[] key, boolean contains, int type) throws IOException {
        GuardRawByteListener listener = new GuardRawByteListener((byte) type, version, null);
        replicator.addRawByteListener(listener);
        SkipRdbParser skip = new SkipRdbParser(in);
        long len = skip.rdbLoadLen().len;
        while (len > 0) {
            skip.rdbLoadEncodedStringObject();
            skip.rdbLoadDoubleValue();
            len--;
        }
        replicator.removeRawByteListener(listener);
        DumpKeyValuePair kv = new DumpKeyValuePair();
        kv.setDb(db);
        kv.setValueRdbType(type);
        kv.setKey(key);
        kv.setValue(listener.getBytes());
        return kv;
    }
    
    @Override
    protected Event doApplyZSet2(RedisInputStream in, DB db, int version, byte[] key, boolean contains, int type) throws IOException {
        GuardRawByteListener listener = new GuardRawByteListener((byte) type, version, null);
        replicator.addRawByteListener(listener);
        SkipRdbParser skip = new SkipRdbParser(in);
        long len = skip.rdbLoadLen().len;
        while (len > 0) {
            skip.rdbLoadEncodedStringObject();
            skip.rdbLoadBinaryDoubleValue();
            len--;
        }
        replicator.removeRawByteListener(listener);
        DumpKeyValuePair kv = new DumpKeyValuePair();
        kv.setDb(db);
        kv.setValueRdbType(type);
        kv.setKey(key);
        kv.setValue(listener.getBytes());
        return kv;
    }
    
    @Override
    protected Event doApplyHash(RedisInputStream in, DB db, int version, byte[] key, boolean contains, int type) throws IOException {
        GuardRawByteListener listener = new GuardRawByteListener((byte) type, version, null);
        replicator.addRawByteListener(listener);
        SkipRdbParser skip = new SkipRdbParser(in);
        long len = skip.rdbLoadLen().len;
        while (len > 0) {
            skip.rdbLoadEncodedStringObject();
            skip.rdbLoadEncodedStringObject();
            len--;
        }
        replicator.removeRawByteListener(listener);
        DumpKeyValuePair kv = new DumpKeyValuePair();
        kv.setDb(db);
        kv.setValueRdbType(type);
        kv.setKey(key);
        kv.setValue(listener.getBytes());
        return kv;
    }
    
    @Override
    protected Event doApplyHashZipMap(RedisInputStream in, DB db, int version, byte[] key, boolean contains, int type) throws IOException {
        GuardRawByteListener listener = new GuardRawByteListener((byte) type, version, null);
        replicator.addRawByteListener(listener);
        new SkipRdbParser(in).rdbLoadPlainStringObject();
        replicator.removeRawByteListener(listener);
        DumpKeyValuePair kv = new DumpKeyValuePair();
        kv.setDb(db);
        kv.setValueRdbType(type);
        kv.setKey(key);
        kv.setValue(listener.getBytes());
        return kv;
    }
    
    @Override
    protected Event doApplyListZipList(RedisInputStream in, DB db, int version, byte[] key, boolean contains, int type) throws IOException {
        GuardRawByteListener listener = new GuardRawByteListener((byte) type, version, null);
        replicator.addRawByteListener(listener);
        new SkipRdbParser(in).rdbLoadPlainStringObject();
        replicator.removeRawByteListener(listener);
        DumpKeyValuePair kv = new DumpKeyValuePair();
        kv.setDb(db);
        kv.setValueRdbType(type);
        kv.setKey(key);
        kv.setValue(listener.getBytes());
        return kv;
    }
    
    @Override
    protected Event doApplySetIntSet(RedisInputStream in, DB db, int version, byte[] key, boolean contains, int type) throws IOException {
        GuardRawByteListener listener = new GuardRawByteListener((byte) type, version, null);
        replicator.addRawByteListener(listener);
        new SkipRdbParser(in).rdbLoadPlainStringObject();
        replicator.removeRawByteListener(listener);
        DumpKeyValuePair kv = new DumpKeyValuePair();
        kv.setDb(db);
        kv.setValueRdbType(type);
        kv.setKey(key);
        kv.setValue(listener.getBytes());
        return kv;
    }
    
    @Override
    protected Event doApplyZSetZipList(RedisInputStream in, DB db, int version, byte[] key, boolean contains, int type) throws IOException {
        GuardRawByteListener listener = new GuardRawByteListener((byte) type, version, null);
        replicator.addRawByteListener(listener);
        new SkipRdbParser(in).rdbLoadPlainStringObject();
        replicator.removeRawByteListener(listener);
        DumpKeyValuePair kv = new DumpKeyValuePair();
        kv.setDb(db);
        kv.setValueRdbType(type);
        kv.setKey(key);
        kv.setValue(listener.getBytes());
        return kv;
    }
    
    @Override
    protected Event doApplyHashZipList(RedisInputStream in, DB db, int version, byte[] key, boolean contains, int type) throws IOException {
        GuardRawByteListener listener = new GuardRawByteListener((byte) type, version, null);
        replicator.addRawByteListener(listener);
        new SkipRdbParser(in).rdbLoadPlainStringObject();
        replicator.removeRawByteListener(listener);
        DumpKeyValuePair kv = new DumpKeyValuePair();
        kv.setDb(db);
        kv.setValueRdbType(type);
        kv.setKey(key);
        kv.setValue(listener.getBytes());
        return kv;
    }
    
    @Override
    protected Event doApplyListQuickList(RedisInputStream in, DB db, int version, byte[] key, boolean contains, int type) throws IOException {
        GuardRawByteListener listener = new GuardRawByteListener((byte) type, version, null);
        replicator.addRawByteListener(listener);
        SkipRdbParser skip = new SkipRdbParser(in);
        long len = skip.rdbLoadLen().len;
        for (int i = 0; i < len; i++) {
            skip.rdbGenericLoadStringObject();
        }
        replicator.removeRawByteListener(listener);
        DumpKeyValuePair kv = new DumpKeyValuePair();
        kv.setDb(db);
        kv.setValueRdbType(type);
        kv.setKey(key);
        kv.setValue(listener.getBytes());
        return kv;
    }
    
    @Override
    protected Event doApplyModule(RedisInputStream in, DB db, int version, byte[] key, boolean contains, int type) throws IOException {
        GuardRawByteListener listener = new GuardRawByteListener((byte) type, version, null);
        replicator.addRawByteListener(listener);
        SkipRdbParser skip = new SkipRdbParser(in);
        char[] c = new char[9];
        long moduleid = skip.rdbLoadLen().len;
        for (int i = 0; i < c.length; i++) {
            c[i] = MODULE_SET[(int) (moduleid >>> (10 + (c.length - 1 - i) * 6) & 63)];
        }
        String moduleName = new String(c);
        int moduleVersion = (int) (moduleid & 1023);
        ModuleParser<? extends Module> moduleParser = lookupModuleParser(moduleName, moduleVersion);
        if (moduleParser == null) {
            throw new NoSuchElementException("module parser[" + moduleName + ", " + moduleVersion + "] not register. rdb type: [RDB_TYPE_MODULE]");
        }
        moduleParser.parse(in, 1);
        replicator.removeRawByteListener(listener);
        DumpKeyValuePair kv = new DumpKeyValuePair();
        kv.setDb(db);
        kv.setValueRdbType(type);
        kv.setKey(key);
        kv.setValue(listener.getBytes());
        return kv;
    }
    
    @Override
    protected Event doApplyModule2(RedisInputStream in, DB db, int version, byte[] key, boolean contains, int type) throws IOException {
        GuardRawByteListener listener = new GuardRawByteListener((byte) type, version, null);
        replicator.addRawByteListener(listener);
        SkipRdbParser skip = new SkipRdbParser(in);
        skip.rdbLoadCheckModuleValue();
        replicator.removeRawByteListener(listener);
        DumpKeyValuePair kv = new DumpKeyValuePair();
        kv.setDb(db);
        kv.setValueRdbType(type);
        kv.setKey(key);
        kv.setValue(listener.getBytes());
        return kv;
    }
    
    @Override
    protected Event doApplyStreamListPacks(RedisInputStream in, DB db, int version, byte[] key, boolean contains, int type) throws IOException {
        GuardRawByteListener listener = new GuardRawByteListener((byte) type, version, null);
        replicator.addRawByteListener(listener);
        SkipRdbParser skip = new SkipRdbParser(in);
        long listPacks = skip.rdbLoadLen().len;
        while (listPacks-- > 0) {
            skip.rdbLoadPlainStringObject();
            skip.rdbLoadPlainStringObject();
        }
        skip.rdbLoadLen();
        skip.rdbLoadLen();
        skip.rdbLoadLen();
        long groupCount = skip.rdbLoadLen().len;
        while (groupCount-- > 0) {
            skip.rdbLoadPlainStringObject();
            skip.rdbLoadLen();
            skip.rdbLoadLen();
            long groupPel = skip.rdbLoadLen().len;
            while (groupPel-- > 0) {
                in.skip(16);
                skip.rdbLoadMillisecondTime();
                skip.rdbLoadLen();
            }
            long consumerCount = skip.rdbLoadLen().len;
            while (consumerCount-- > 0) {
                skip.rdbLoadPlainStringObject();
                skip.rdbLoadMillisecondTime();
                long consumerPel = skip.rdbLoadLen().len;
                while (consumerPel-- > 0) {
                    in.skip(16);
                }
            }
        }
        replicator.removeRawByteListener(listener);
        DumpKeyValuePair kv = new DumpKeyValuePair();
        kv.setDb(db);
        kv.setValueRdbType(type);
        kv.setKey(key);
        kv.setValue(listener.getBytes());
        return kv;
    }
}