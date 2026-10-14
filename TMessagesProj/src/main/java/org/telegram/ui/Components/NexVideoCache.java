package org.telegram.ui.Components;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;

// NexGramm: one disk cache for Lenta / story videos streamed over https, so a video seen once (or the part
// buffered in the feed) plays at once again and in full screen. Least recently used parts go first.
@OptIn(markerClass = UnstableApi.class)
public final class NexVideoCache {

    private static final long MAX_BYTES = 200L * 1024 * 1024;
    private static SimpleCache cache;
    private static boolean failed;

    private NexVideoCache() {
    }

    private static synchronized SimpleCache cache() {
        if (cache == null && !failed) {
            try {
                final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "nex_video");
                cache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    new StandaloneDatabaseProvider(ApplicationLoader.applicationContext));
            } catch (Throwable e) {
                failed = true; // e.g. the folder is locked: stream without the cache
                FileLog.e(e);
            }
        }
        return cache;
    }

    // The upstream source behind the cache; the upstream as it is when the cache cannot be used
    static DataSource.Factory wrap(DataSource.Factory upstream) {
        final SimpleCache c = cache();
        if (c == null) {
            return upstream;
        }
        return new CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }
}
