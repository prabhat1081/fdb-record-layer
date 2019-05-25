/*
 * LocatableResolverTest.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2018 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.provider.foundationdb.keyspace;

import com.apple.foundationdb.Range;
import com.apple.foundationdb.async.AsyncUtil;
import com.apple.foundationdb.async.MoreAsyncUtil;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.provider.foundationdb.FDBDatabase;
import com.apple.foundationdb.record.provider.foundationdb.FDBDatabaseFactory;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordContext;
import com.apple.foundationdb.record.provider.foundationdb.FDBStoreTimer;
import com.apple.foundationdb.record.provider.foundationdb.FDBTestBase;
import com.apple.foundationdb.record.provider.foundationdb.keyspace.LocatableResolver.LocatableResolverLockedException;
import com.apple.foundationdb.record.provider.foundationdb.keyspace.ResolverCreateHooks.MetadataHook;
import com.apple.foundationdb.record.provider.foundationdb.keyspace.ResolverCreateHooks.PreWriteCheck;
import com.apple.foundationdb.tuple.Tuple;
import com.apple.test.Tags;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheStats;
import com.google.common.collect.ImmutableList;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.apple.foundationdb.record.TestHelpers.ExceptionMessageMatcher.hasMessageContaining;
import static com.apple.foundationdb.record.TestHelpers.consistently;
import static com.apple.foundationdb.record.TestHelpers.eventually;
import static com.apple.foundationdb.record.provider.foundationdb.keyspace.KeySpaceDirectory.KeyType;
import static com.apple.foundationdb.record.provider.foundationdb.keyspace.ResolverCreateHooks.DEFAULT_CHECK;
import static com.apple.foundationdb.record.provider.foundationdb.keyspace.ResolverCreateHooks.DEFAULT_HOOK;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for {@link LocatableResolver}.
 */
@Tag(Tags.WipesFDB)
@Tag(Tags.RequiresFDB)
public abstract class LocatableResolverTest extends FDBTestBase {
    protected FDBDatabase database;
    protected LocatableResolver globalScope;
    protected BiFunction<FDBDatabase, ResolvedKeySpacePath, LocatableResolver> scopedDirectoryGenerator;
    protected Function<FDBDatabase, LocatableResolver> globalScopeGenerator;
    protected Random random;

    @BeforeEach
    public void setup() {
        FDBDatabaseFactory factory = FDBDatabaseFactory.instance();
        factory.setDirectoryCacheSize(100);
        long seed = System.currentTimeMillis();
        System.out.println("Seed " + seed);
        random = new Random(seed);
        database = factory.getDatabase();
        // clear all state in the db
        database.close();
        // resets the lock cache
        database.setResolverStateRefreshTimeMillis(30000);
        globalScope = globalScopeGenerator.apply(database);
        wipeFDB();
    }

    @Test
    public void testLookupCaching() {
        KeySpace keySpace = new KeySpace(new KeySpaceDirectory("path", KeyType.STRING, "path"));

        ResolvedKeySpacePath path1;
        try (FDBRecordContext context = database.openContext()) {
            path1 = keySpace.resolveFromKey(context, Tuple.from("path"));
        }
        LocatableResolver resolver = scopedDirectoryGenerator.apply(database, path1);

        Long value = resolver.resolve(null, "foo").join();

        for (int i = 0; i < 5; i++) {
            Long fetched = resolver.resolve(null, "foo").join();
            assertThat("we should always get the original value", fetched, is(value));
        }
        CacheStats stats = database.getDirectoryCacheStats();
        assertThat("subsequent lookups should hit the cache", stats.hitCount(), is(5L));
    }

    @Test
    public void testDirectoryIsolation() {
        KeySpace keySpace = new KeySpace(
                new KeySpaceDirectory("path", KeyType.STRING, "path")
                        .addSubdirectory(new KeySpaceDirectory("to", KeyType.STRING, "to")
                                .addSubdirectory(new KeySpaceDirectory("dirLayer1", KeyType.STRING, "dirLayer1"))
                                .addSubdirectory(new KeySpaceDirectory("dirLayer2", KeyType.STRING, "dirLayer2"))
                        )
        );

        try (FDBRecordContext context = database.openContext()) {
            ResolvedKeySpacePath path1 = keySpace.resolveFromKey(context, Tuple.from("path", "to", "dirLayer1"));
            ResolvedKeySpacePath path2 = keySpace.resolveFromKey(context, Tuple.from("path", "to", "dirLayer2"));
            LocatableResolver resolver = scopedDirectoryGenerator.apply(database, path1);
            LocatableResolver sameResolver = scopedDirectoryGenerator.apply(database, path1);
            LocatableResolver differentResolver = scopedDirectoryGenerator.apply(database, path2);

            List<String> names = ImmutableList.of("a", "set", "of", "names", "to", "resolve");
            List<Long> resolved = new ArrayList<>();
            List<Long> same = new ArrayList<>();
            List<Long> different = new ArrayList<>();
            for (String name : names) {
                resolved.add(resolver.resolve(context.getTimer(), name).join());
                same.add(sameResolver.resolve(context.getTimer(), name).join());
                different.add(differentResolver.resolve(context.getTimer(), name).join());
            }
            assertThat("same resolvers produce identical results", resolved, contains(same.toArray()));
            assertThat("different resolvers are independent", resolved, not(contains(different.toArray())));
        }
    }

    @Test
    public void testScopedCaching() {
        KeySpace keySpace = new KeySpace(
                new KeySpaceDirectory("path1", KeyType.STRING, "path1"),
                new KeySpaceDirectory("path2", KeyType.STRING, "path2")
        );

        try (FDBRecordContext context = database.openContext()) {
            final ResolvedKeySpacePath path1 = keySpace.resolveFromKey(context, Tuple.from("path1"));
            final LocatableResolver resolver1 = scopedDirectoryGenerator.apply(database, path1);

            Cache<ScopedValue<String>, Long> cache = CacheBuilder.newBuilder().build();
            cache.put(resolver1.wrap("stuff"), 1L);

            assertThat("values can be read from the cache by scoped string",
                    cache.getIfPresent(resolver1.wrap("stuff")), is(1L));
            assertThat("cache misses when looking for unknown name in scope",
                    cache.getIfPresent(resolver1.wrap("missing")), equalTo(null));

            final ResolvedKeySpacePath path2 = keySpace.resolveFromKey(context, Tuple.from("path2"));
            final LocatableResolver resolver2 = scopedDirectoryGenerator.apply(database, path2);
            assertThat("cache misses when string is not in this scope",
                    cache.getIfPresent(resolver2.wrap("stuff")), equalTo(null));

            final LocatableResolver newResolver = scopedDirectoryGenerator.apply(database, path1);
            assertThat("scoping is determined by value of scope directory, not a reference to it",
                    cache.getIfPresent(newResolver.wrap("stuff")), is(1L));
        }
    }

    @Test
    public void testDirectoryCache() {
        FDBDatabaseFactory factory = FDBDatabaseFactory.instance();
        factory.setDirectoryCacheSize(10);

        FDBStoreTimer timer = new FDBStoreTimer();


        FDBDatabase fdb = factory.getDatabase();
        fdb.close(); // Make sure cache is fresh.
        String key = "world";
        Long value;
        try (FDBRecordContext context = fdb.openContext()) {
            context.setTimer(timer);
            value = context.asyncToSync(FDBStoreTimer.Waits.WAIT_DIRECTORY_RESOLVE, globalScope.resolve(context.getTimer(), key));
        }
        int initialReads = timer.getCount(FDBStoreTimer.Events.DIRECTORY_READ);
        assertThat(initialReads, is(greaterThanOrEqualTo(1)));

        for (int i = 0; i < 10; i++) {
            try (FDBRecordContext context = fdb.openContext()) {
                context.setTimer(timer);
                assertThat("we continue to resolve the same value",
                        context.asyncToSync(FDBStoreTimer.Waits.WAIT_DIRECTORY_RESOLVE, globalScope.resolve(context.getTimer(), key)),
                        is(value));
            }
        }
        assertEquals(timer.getCount(FDBStoreTimer.Events.DIRECTORY_READ), initialReads);
    }

    @Test
    public void testResolveUseCacheCommits() {
        FDBDatabaseFactory factory = FDBDatabaseFactory.instance();
        factory.setDirectoryCacheSize(10);

        FDBStoreTimer timer = new FDBStoreTimer();
        String key = "hello " + UUID.randomUUID();
        FDBDatabase fdb = factory.getDatabase();

        assertEquals(0, timer.getCount(FDBStoreTimer.Events.COMMIT));
        try (FDBRecordContext context = fdb.openContext()) {
            context.setTimer(timer);
            context.asyncToSync(FDBStoreTimer.Waits.WAIT_DIRECTORY_RESOLVE, globalScope.resolve(context.getTimer(), key));
        }
        // initial resolve may commit twice, once for the key and once to initialize the reverse directory cache
        assertThat(timer.getCount(FDBStoreTimer.Events.COMMIT), is(greaterThanOrEqualTo(1)));

        timer.reset();
        try (FDBRecordContext context = fdb.openContext()) {
            context.setTimer(timer);
            context.asyncToSync(FDBStoreTimer.Waits.WAIT_DIRECTORY_RESOLVE, globalScope.resolve(context.getTimer(), "a-new-key"));
        }
        assertEquals(1, timer.getCount(FDBStoreTimer.Events.COMMIT));


        timer.reset();
        assertEquals(0, timer.getCount(FDBStoreTimer.Events.COMMIT));
        try (FDBRecordContext context = fdb.openContext()) {
            context.setTimer(timer);
            context.asyncToSync(FDBStoreTimer.Waits.WAIT_DIRECTORY_RESOLVE, globalScope.resolve(context.getTimer(), key));
        }
        assertEquals(0, timer.getCount(FDBStoreTimer.Events.COMMIT));
    }

    @Test
    public void testResolveCommitsWhenCacheEnabled() {
        Map<String, Long> mappings = new HashMap<>();
        try (FDBRecordContext context = database.openContext()) {
            for (int i = 0; i < 10; i++) {
                String key = "string-" + i;
                Long value = globalScope.resolve(context.getTimer(), key).join();
                mappings.put(key, value);
            }
        }

        Long baseline = database.getDirectoryCacheStats().hitCount();
        Long reverseCacheBaseline = database.getReverseDirectoryInMemoryCache().stats().hitCount();
        database.close();
        database = FDBDatabaseFactory.instance().getDatabase();
        try (FDBRecordContext context = database.openContext()) {
            for (Map.Entry<String, Long> entry : mappings.entrySet()) {
                Long value = globalScope.resolve(context.getTimer(), entry.getKey()).join();
                String name = globalScope.reverseLookup(null, value).join();
                assertEquals(value, entry.getValue(), "mapping is persisted even though context in arg was not committed");
                assertEquals(name, entry.getKey(), "reverse mapping is persisted even though context in arg was not committed");
            }
            assertEquals(database.getDirectoryCacheStats().hitCount() - baseline, 0L, "values are persisted, not in cache");
            assertEquals(database.getReverseDirectoryInMemoryCache().stats().hitCount() - reverseCacheBaseline, 0L, "values are persisted, not in cache");
        }
    }

    @Test
    public void testResolveWithNoMetadata() {
        Long value;
        ResolverResult noHookResult;
        value = globalScope.resolve(null, "resolve-string").join();
        noHookResult = globalScope.resolveWithMetadata(null, "resolve-string", ResolverCreateHooks.getDefault()).join();
        assertThat("the value is the same", noHookResult.getValue(), is(value));
        assertThat("entry was created without metadata", noHookResult.getMetadata(), is(nullValue()));
    }

    @Test
    public void testReverseLookup() {
        Long value;
        try (FDBRecordContext context = database.openContext()) {
            value = globalScope.resolve(context.getTimer(), "something").join();
            context.commit();
        }

        String lookupString = globalScope.reverseLookup(null, value).join();
        assertThat("reverse lookup works in a new context", lookupString, is("something"));
    }

    @Test
    public void testReverseLookupNotFound() {
        try {
            globalScope.reverseLookup(null, -1L).join();
            fail("should throw CompletionException");
        } catch (CompletionException ex) {
            assertThat(ex.getCause(), is(instanceOf(NoSuchElementException.class)));
        }
    }

    @Test
    public void testReverseLookupCaching() {
        Long value;
        try (FDBRecordContext context = database.openContext()) {
            value = globalScope.resolve(context.getTimer(), "something").join();
            context.commit();
        }

        database.clearForwardDirectoryCache();
        long baseHitCount = database.getReverseDirectoryInMemoryCache().stats().hitCount();
        long baseMissCount = database.getReverseDirectoryInMemoryCache().stats().missCount();

        String name = globalScope.reverseLookup(null, value).join();
        assertThat("reverse lookup gives previous result", name, is("something"));
        long hitCount = database.getReverseDirectoryInMemoryCache().stats().hitCount();
        long missCount = database.getReverseDirectoryInMemoryCache().stats().missCount();
        assertEquals(0L, hitCount - baseHitCount);
        assertEquals(1L, missCount - baseMissCount);

        // repeated lookups use the in-memory cache
        for (int i = 0; i < 10; i++) {
            name = globalScope.reverseLookup(null, value).join();
            assertThat("reverse lookup gives the same result", name, is("something"));
        }
        hitCount = database.getReverseDirectoryInMemoryCache().stats().hitCount();
        missCount = database.getReverseDirectoryInMemoryCache().stats().missCount();
        assertEquals(10L, hitCount - baseHitCount);
        assertEquals(1L, missCount - baseMissCount);
    }

    @Test
    public void testCacheConsistency() {
        final AtomicBoolean keepRunning = new AtomicBoolean(true);
        final List<Pair<String, ResolverResult>> cacheHits = new ArrayList<>();
        final List<Pair<Long, String>> reverseCacheHits = new ArrayList<>();
        CompletableFuture<Void> loopOperation = AsyncUtil.whileTrue(() ->
                MoreAsyncUtil.delayedFuture(1, TimeUnit.MILLISECONDS)
                        .thenRun(() -> gatherCacheHits(database.getDirectoryCache(/* always return current version */ 0), cacheHits))
                        .thenRun(() -> gatherCacheHits(database.getReverseDirectoryInMemoryCache(), reverseCacheHits))
                        .thenApply(ignored2 -> keepRunning.get())
        );

        List<CompletableFuture<Pair<String, ResolverResult>>> allocationOperations = IntStream.range(0, 50)
                .mapToObj(i -> "string-" + i)
                .map(name -> database.run(ctx -> globalScope.resolveWithMetadata(ctx.getTimer(), name, ResolverCreateHooks.getDefault()))
                        .thenApply(result -> Pair.of(name, result))
                )
                .collect(Collectors.toList());

        List<Pair<String, ResolverResult>> allocations = AsyncUtil.getAll(allocationOperations).thenApply(list -> {
            keepRunning.set(false);
            return list;
        }).join();
        loopOperation.join();

        List<Pair<Long, String>> reverseAllocations = allocations.stream()
                .map(e -> Pair.of(e.getValue().getValue(), e.getKey()))
                .collect(Collectors.toList());

        validateCacheHits(cacheHits, allocations,
                (context, name) -> globalScope.resolveWithMetadata(context.getTimer(), name, ResolverCreateHooks.getDefault()));
        validateCacheHits(reverseCacheHits, reverseAllocations,
                (context, value) -> globalScope.reverseLookup(null, value));
    }

    private <K, V> void validateCacheHits(List<Pair<K, V>> cacheHits,
                                          List<Pair<K, V>> allocations,
                                          BiFunction<FDBRecordContext, K, CompletableFuture<V>> persistedMappingSupplier) {
        for (Pair<K, V> pair : cacheHits) {
            V persistedMapping;
            try (FDBRecordContext context = database.openContext()) {
                persistedMapping = persistedMappingSupplier.apply(context, pair.getKey()).join();
            }
            assertThat("every cache hit corresponds to an allocation", allocations, hasItem(equalTo(pair)));
            assertEquals(persistedMapping, pair.getValue(), "all cache hits correspond to a persisted mapping");
        }
    }

    private <K, V> void gatherCacheHits(Cache<ScopedValue<K>, V> cache, List<Pair<K, V>> cacheHits) {
        cacheHits.addAll(
                cache.asMap().entrySet().stream().map(e -> Pair.of(e.getKey().getData(), e.getValue())).collect(Collectors.toList())
        );
    }

    @Test
    public void testParallelSet() {
        String key = "some-random-key-" + random.nextLong();
        List<CompletableFuture<Long>> allocations = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            allocations.add(allocateInNewContext(key, globalScope));
        }
        Set<Long> allocationSet = new HashSet<>(AsyncUtil.getAll(allocations).join());

        assertThat("only one value is allocated", allocationSet, hasSize(1));
    }

    private CompletableFuture<Long> allocateInNewContext(String key, LocatableResolver resolver) {
        FDBRecordContext context = database.openContext();
        return resolver.resolve(context.getTimer(), key).whenComplete((ignore1, ignore2) -> context.close());
    }

    @Test
    public void testWriteLockCaching() {
        FDBStoreTimer timer = new FDBStoreTimer();
        try (FDBRecordContext context = database.openContext()) {
            context.setTimer(timer);
            globalScope.resolve(context.getTimer(), "something").join();
            int initialCount = timer.getCount(FDBStoreTimer.DetailEvents.RESOLVER_STATE_READ);
            assertThat("first read must check the lock in the database", initialCount, greaterThanOrEqualTo(1));

            timer.reset();
            int oldCount = timer.getCount(FDBStoreTimer.DetailEvents.RESOLVER_STATE_READ);
            for (int i = 0; i < 10; i++) {
                globalScope.resolve(context.getTimer(), "something-" + i).join();

                // depending on the nature of the write safety check we may need to read the key multiple times
                // so assert that we do at least one read on each resolve
                int currentCount = timer.getCount(FDBStoreTimer.DetailEvents.RESOLVER_STATE_READ);
                assertThat("subsequent writes must also check the key",
                        currentCount, is(greaterThan(oldCount)));
                oldCount = currentCount;
            }

            timer.reset();
            for (int i = 0; i < 10; i++) {
                globalScope.resolve(context.getTimer(), "something-" + i).join();
            }
            assertThat("reads do not need to check the key",
                    timer.getCount(FDBStoreTimer.DetailEvents.RESOLVER_STATE_READ), is(0));
        }

        FDBDatabaseFactory.instance().clear();
        FDBDatabase newDatabase = FDBDatabaseFactory.instance().getDatabase();
        FDBStoreTimer timer2 = new FDBStoreTimer();
        try (FDBRecordContext context = newDatabase.openContext()) {
            context.setTimer(timer2);
            globalScope.resolve(context.getTimer(), "something").join();
            assertThat("state is loaded from the new database",
                    timer2.getCount(FDBStoreTimer.DetailEvents.RESOLVER_STATE_READ), is(1));
        }
    }

    @Test
    public void testCachingPerDbPerResolver() {
        KeySpace keySpace = new KeySpace(
                new KeySpaceDirectory("resolver1", KeyType.STRING, "resolver1"),
                new KeySpaceDirectory("resolver2", KeyType.STRING, "resolver2"));

        LocatableResolver resolver1;
        LocatableResolver resolver2;

        FDBStoreTimer timer = new FDBStoreTimer();
        try (FDBRecordContext context = database.openContext()) {
            resolver1 = scopedDirectoryGenerator.apply(database, keySpace.path("resolver1").toResolvedPath(context));
            resolver2 = scopedDirectoryGenerator.apply(database, keySpace.path("resolver2").toResolvedPath(context));

            context.setTimer(timer);
            for (int i = 0; i < 10; i++) {
                resolver1.getVersion(context.getTimer()).join();
            }
            assertThat("We only read the value once", timer.getCount(FDBStoreTimer.DetailEvents.RESOLVER_STATE_READ), is(1));

            timer = new FDBStoreTimer();
            assertThat("count is reset", timer.getCount(FDBStoreTimer.DetailEvents.RESOLVER_STATE_READ), is(0));

            context.setTimer(timer);
            resolver2.getVersion(context.getTimer()).join();
            assertThat("We have to read the value for the new resolver", timer.getCount(FDBStoreTimer.DetailEvents.RESOLVER_STATE_READ), is(1));

            LocatableResolver newResolver1 = scopedDirectoryGenerator.apply(database, keySpace.path("resolver1").toResolvedPath(context));
            timer = new FDBStoreTimer();
            assertThat("count is reset", timer.getCount(FDBStoreTimer.DetailEvents.RESOLVER_STATE_READ), is(0));

            context.setTimer(timer);
            for (int i = 0; i < 10; i++) {
                newResolver1.getVersion(context.getTimer()).join();
            }
            assertThat("we still hit the cache", timer.getCount(FDBStoreTimer.DetailEvents.RESOLVER_STATE_READ), is(0));
        }
    }

    @Test
    public void testEnableDisableWriteLock() {
        database.setResolverStateRefreshTimeMillis(100);

        final Long value;
        try (FDBRecordContext context = database.openContext()) {
            // resolver starts in unlocked state
            value = globalScope.resolve(context.getTimer(), "some-string").join();
        }

        globalScope.enableWriteLock().join();

        assertLocked(database, globalScope);
        try (FDBRecordContext context = database.openContext()) {
            consistently("we should still be able to read the old value", () ->
                    globalScope.resolve(context.getTimer(), "some-string").join(), is(value), 100, 10);
        }

        globalScope.disableWriteLock().join();
        try (FDBRecordContext context = database.openContext()) {
            eventually("writes should succeed", () -> {
                try {
                    globalScope.resolve(context.getTimer(), "random-value-" + random.nextLong()).join();
                } catch (CompletionException exception) {
                    return exception.getCause();
                }
                return null;
            }, is(nullValue()), 120, 10);
            consistently("writes should continue to succeed", () -> {
                try {
                    globalScope.resolve(context.getTimer(), "random-value-" + random.nextLong()).join();
                } catch (CompletionException exception) {
                    return exception.getCause();
                }
                return null;
            }, is(nullValue()), 100, 10);
        }
    }

    @Test
    public void testExclusiveLock() {
        // version is cached for 30 seconds by default
        database.setResolverStateRefreshTimeMillis(100);

        globalScope.exclusiveLock().join();
        assertLocked(database, globalScope);

        try {
            globalScope.exclusiveLock().join();
            fail("lock already enabled, should fail");
        } catch (CompletionException ex) {
            assertThat("we get the correct cause", ex.getCause(), allOf(
                    instanceOf(LocatableResolverLockedException.class),
                    hasMessageContaining("resolver must be unlocked to get exclusive lock")
            ));
        }
    }

    @Test
    public void testExclusiveLockParallel() {
        // test that when parallel threads/instances attempt to get the exclusive lock only one will win.
        List<CompletableFuture<Void>> parallelGets = new ArrayList<>();
        AtomicInteger lockGetCount = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            parallelGets.add(globalScope.exclusiveLock().handle((ignore, ex) -> {
                if (ex == null) {
                    lockGetCount.incrementAndGet();
                    return null;
                } else if (ex instanceof LocatableResolverLockedException ||
                           (ex instanceof CompletionException && ex.getCause() instanceof LocatableResolverLockedException)) {
                    return null;
                }
                throw new AssertionError("unexpected error", ex);
            }));
        }
        CompletableFuture.allOf(parallelGets.toArray(new CompletableFuture<?>[0])).join();

        assertThat("only one exclusiveLock succeeds", lockGetCount.get(), is(1));
        assertLocked(database, globalScope);
    }

    private void assertLocked(@Nonnull final FDBDatabase database, @Nonnull final LocatableResolver resolver) {
        try (FDBRecordContext context = database.openContext()) {
            eventually("write lock is enabled", () -> {
                try {
                    resolver.resolve(context.getTimer(), "random-value-" + random.nextLong()).join();
                } catch (CompletionException exception) {
                    return exception.getCause();
                }
                return null;
            }, allOf(instanceOf(LocatableResolverLockedException.class),
                    hasMessageContaining("locatable resolver is not writable")), 120, 10);
            consistently("write lock remains enabled", () -> {
                try {
                    resolver.resolve(context.getTimer(), "random-value-" + random.nextLong()).join();
                } catch (CompletionException exception) {
                    return exception.getCause();
                }
                return null;
            }, allOf(instanceOf(LocatableResolverLockedException.class),
                    hasMessageContaining("locatable resolver is not writable")), 120, 10);
        }
    }

    @Test
    public void testGetVersion() {
        // version is cached for 30 seconds by default
        database.setResolverStateRefreshTimeMillis(100);

        consistently("uninitialized version is 0", () -> {
            try (FDBRecordContext context = database.openContext()) {
                return globalScope.getVersion(context.getTimer()).join();
            }
        }, is(0), 200, 10);
        globalScope.incrementVersion().join();
        eventually("version changes to 1", () -> {
            try (FDBRecordContext context = database.openContext()) {
                return globalScope.getVersion(context.getTimer()).join();
            }
        }, is(1), 120, 10);
        globalScope.incrementVersion().join();
        eventually("version changes to 2", () -> {
            try (FDBRecordContext context = database.openContext()) {
                return globalScope.getVersion(context.getTimer()).join();
            }
        }, is(2), 120, 10);
    }

    @Test
    public void testParallelDbAndScopeGetVersion() {
        // version is cached for 30 seconds by default
        database.setResolverStateRefreshTimeMillis(100);
        // sets the timeout for all the db instances we create
        final FDBDatabaseFactory parallelFactory = new FDBDatabaseFactory();
        parallelFactory.setStateRefreshTimeMillis(100);
        Supplier<FDBDatabase> databaseSupplier = () -> new FDBDatabase(parallelFactory, null);
        consistently("uninitialized version is 0", () -> {
            try (FDBRecordContext context = database.openContext()) {
                return globalScope.getVersion(context.getTimer()).join();
            }
        }, is(0), 200, 10);

        List<Pair<FDBDatabase, LocatableResolver>> simulatedInstances = IntStream.range(0, 20)
                .mapToObj(i -> {
                    FDBDatabase db = databaseSupplier.get();
                    return Pair.of(db, globalScopeGenerator.apply(db));
                })
                .collect(Collectors.toList());

        Supplier<CompletableFuture<Set<Integer>>> supplier = () -> {
            List<CompletableFuture<Integer>> parallelOperations = simulatedInstances.stream()
                    .map(pair -> {
                        FDBDatabase db = pair.getKey();
                        LocatableResolver resolver = pair.getValue();
                        FDBRecordContext context = db.openContext();
                        return resolver.getVersion(context.getTimer()).whenComplete((ignore, e) -> context.close());
                    }).collect(Collectors.toList());
            return AsyncUtil.getAll(parallelOperations).thenApply(HashSet::new);
        };

        consistently("all instances report the version as 0", () -> supplier.get().join(),
                is(Collections.singleton(0)), 200, 10);
        globalScope.incrementVersion().join();
        eventually("all instances report the new version once the caches have refreshed", () -> supplier.get().join(),
                is(Collections.singleton(1)), 120, 10);
    }

    @Test
    public void testVersionIncrementInvalidatesCache() {
        FDBDatabaseFactory factory = FDBDatabaseFactory.instance();
        factory.setDirectoryCacheSize(10);
        FDBStoreTimer timer = new FDBStoreTimer();
        FDBDatabase fdb = factory.getDatabase();
        fdb.close(); // Make sure cache is fresh, and resets version
        fdb.setResolverStateRefreshTimeMillis(100);
        String key = "some-key";
        Long value;
        try (FDBRecordContext context = fdb.openContext()) {
            context.setTimer(timer);
            value = context.asyncToSync(FDBStoreTimer.Waits.WAIT_DIRECTORY_RESOLVE, globalScope.resolve(context.getTimer(), key));
        }
        assertThat(timer.getCount(FDBStoreTimer.Events.DIRECTORY_READ), is(greaterThanOrEqualTo(1)));

        timer.reset();
        consistently("we hit the cached value", () -> {
            try (FDBRecordContext context = fdb.openContext()) {
                context.setTimer(timer);
                assertThat("the resolved value is still the same", globalScope.resolve(context.getTimer(), key).join(), is(value));
            }
            return timer.getCount(FDBStoreTimer.Events.DIRECTORY_READ);
        }, is(0), 200, 10);
        globalScope.incrementVersion().join();
        timer.reset();
        eventually("we see the version change and invalidate the cache", () -> {
            try (FDBRecordContext context = fdb.openContext()) {
                context.setTimer(timer);
                assertThat("the resolved value is still the same", globalScope.resolve(context.getTimer(), key).join(), is(value));
            }
            return timer.getCount(FDBStoreTimer.Events.DIRECTORY_READ);
        }, is(1), 120, 10);
        timer.reset();
        consistently("the value is cached while the version is not changed", () -> {
            try (FDBRecordContext context = fdb.openContext()) {
                context.setTimer(timer);
                assertThat("the resolved value is still the same", globalScope.resolve(context.getTimer(), key).join(), is(value));
            }
            return timer.getCount(FDBStoreTimer.Events.DIRECTORY_READ);
        }, is(0), 200, 10);
    }

    @Test
    public void testWriteSafetyCheck() {
        KeySpace keySpace = new KeySpace(
                new KeySpaceDirectory("path1", KeyType.STRING, "path1"),
                new KeySpaceDirectory("path2", KeyType.STRING, "path2")
        );

        final LocatableResolver path1Resolver;
        final LocatableResolver path2Resolver;
        try (FDBRecordContext context = database.openContext()) {
            ResolvedKeySpacePath path1 = keySpace.path("path1").toResolvedPath(context);
            ResolvedKeySpacePath path2 = keySpace.path("path2").toResolvedPath(context);
            path1Resolver = scopedDirectoryGenerator.apply(database, path1);
            path2Resolver = scopedDirectoryGenerator.apply(database, path2);
        }

        PreWriteCheck validCheck = (context, resolver) ->
                CompletableFuture.completedFuture(Objects.equals(path1Resolver, resolver));
        PreWriteCheck invalidCheck = (context, resolver) ->
                CompletableFuture.completedFuture(Objects.equals(path2Resolver, resolver));

        ResolverCreateHooks validHooks = new ResolverCreateHooks(validCheck, DEFAULT_HOOK);
        ResolverCreateHooks invalidHooks = new ResolverCreateHooks(invalidCheck, DEFAULT_HOOK);
        Long value = path1Resolver.resolve(null, "some-key", validHooks).join();
        try (FDBRecordContext context = database.openContext()) {
            assertThat("it succeeds and writes the value", path1Resolver.mustResolve(context, "some-key").join(), is(value));
        }

        assertThat("when reading the same key it doesn't perform the check", path1Resolver.resolve(null, "some-key", invalidHooks).join(), is(value));

        try {
            path1Resolver.resolve(null, "another-key", invalidHooks).join();
            fail("should throw CompletionException");
        } catch (CompletionException ex) {
            assertThat("it has the correct cause", ex.getCause(), is(instanceOf(LocatableResolverLockedException.class)));
            assertThat(ex, hasMessageContaining("prewrite check failed"));
        }
    }

    @Test
    public void testResolveWithMetadata() {
        byte[] metadata = Tuple.from("some-metadata").pack();
        MetadataHook hook = ignore -> metadata;
        final ResolverResult result;
        final ResolverCreateHooks hooks = new ResolverCreateHooks(DEFAULT_CHECK, hook);
        result = globalScope.resolveWithMetadata(null, "a-key", hooks).join();
        assertArrayEquals(metadata, result.getMetadata());

        // check that the result with metadata is persisted to the database
        ResolverResult expected = new ResolverResult(result.getValue(), metadata);
        try (FDBRecordContext context = database.openContext()) {
            ResolverResult resultFromDB = globalScope.mustResolveWithMetadata(context, "a-key").join();
            assertEquals(expected.getValue(), resultFromDB.getValue());
            assertArrayEquals(expected.getMetadata(), resultFromDB.getMetadata());
        }

        assertEquals(expected, globalScope.resolveWithMetadata(null, "a-key", hooks).join());

        byte[] newMetadata = Tuple.from("some-different-metadata").pack();
        MetadataHook newHook = ignore -> newMetadata;
        final ResolverCreateHooks newHooks = new ResolverCreateHooks(DEFAULT_CHECK, newHook);

        // make sure we don't just read the cached value
        database.clearCaches();
        assertArrayEquals(metadata, globalScope.resolveWithMetadata(null, "a-key", newHooks).join().getMetadata(),
                "hook is only run on create, does not update metadata");
    }

    @Test
    public void testUpdateMetadata() {
        database.setResolverStateRefreshTimeMillis(100);

        final byte[] oldMetadata = Tuple.from("old").pack();
        final byte[] newMetadata = Tuple.from("new").pack();
        final ResolverCreateHooks hooks = new ResolverCreateHooks(DEFAULT_CHECK, ignore -> oldMetadata);

        ResolverResult initialResult;
        try (FDBRecordContext context = database.openContext()) {
            initialResult = globalScope.resolveWithMetadata(context.getTimer(), "some-key", hooks).join();
            assertArrayEquals(initialResult.getMetadata(), oldMetadata);
        }

        globalScope.updateMetadataAndVersion("some-key", newMetadata).join();
        ResolverResult expected = new ResolverResult(initialResult.getValue(), newMetadata);
        eventually("we see the new metadata", () ->
                        globalScope.resolveWithMetadata(null, "some-key", hooks).join(),
                is(expected), 120, 10);
    }

    @Test
    public void testSetMapping() {
        Long value;
        try (FDBRecordContext context = database.openContext()) {
            value = globalScope.resolve(context.getTimer(), "an-existing-mapping").join();
        }

        try (FDBRecordContext context = database.openContext()) {
            globalScope.setMapping(context, "a-new-mapping", 99L).join();
            globalScope.setMapping(context, "an-existing-mapping", value).join();


            // need to commit before we will be able to see the mapping with resolve
            // since resolve always uses a separate transaction
            context.commit();
        }

        try (FDBRecordContext context = database.openContext()) {
            assertThat("we can see the new mapping", globalScope.resolve(context.getTimer(), "a-new-mapping").join(), is(99L));
            assertThat("we can see the new reverse mapping", globalScope.reverseLookup(null, 99L).join(), is("a-new-mapping"));
            assertThat("we can see the existing mapping", globalScope.resolve(context.getTimer(), "an-existing-mapping").join(), is(value));
            assertThat("we can see the existing reverse mapping", globalScope.reverseLookup(null, value).join(), is("an-existing-mapping"));
        }
    }

    @Test
    public void testSetMappingWithConflicts() {
        Long value;
        try (FDBRecordContext context = database.openContext()) {
            value = globalScope.resolve(context.getTimer(), "an-existing-mapping").join();
        }

        try (FDBRecordContext context = database.openContext()) {
            globalScope.setMapping(context, "an-existing-mapping", value + 1).join();
            fail("should throw an exception");
        } catch (CompletionException ex) {
            assertThat("cause is a record core exception", ex.getCause(), is(instanceOf(RecordCoreException.class)));
            assertThat("it has a helpful message", ex.getCause(),
                    hasMessageContaining("mapping already exists with different value"));
        }

        try (FDBRecordContext context = database.openContext()) {
            assertThat("will still only see the original mapping",
                    globalScope.mustResolve(context, "an-existing-mapping").join(), is(value));
            assertThat("will still only see the original reverse mapping",
                    globalScope.reverseLookup(null, value).join(), is("an-existing-mapping"));
            try {
                String key = globalScope.reverseLookup(null, value + 1).join();
                // there is a small chance that the mapping does exist, but it should be for some other key
                assertThat(key, is(not("an-existing-mapping")));
            } catch (CompletionException ex) {
                // we will get an exception if the reverse mapping does not exist, most of the time this will be the case
                assertThat("no such element on reverse lookup", ex.getCause(), is(instanceOf(NoSuchElementException.class)));
            }
        }


        try (FDBRecordContext context = database.openContext()) {
            globalScope.setMapping(context, "a-different-key", value).join();
            fail("should throw an exception");
        } catch (CompletionException ex) {
            assertThat("cause is a record core exception", ex.getCause(), is(instanceOf(RecordCoreException.class)));
            assertThat("it has a helpful message", ex.getCause(),
                    hasMessageContaining("reverse mapping already exists with different key"));
        }

        try (FDBRecordContext context = database.openContext()) {
            assertThrows(CompletionException.class, () -> globalScope.mustResolve(context, "a-different-key").join(),
                    "nothing is added for that key");
        }
    }

    @Test
    public void testSetWindow() {
        Map<String, Long> oldMappings = new HashMap<>();
        try (FDBRecordContext context = database.openContext()) {
            for (int i = 0; i < 20; i++) {
                String key = "old-resolved-" + i;
                Long value = globalScope.resolve(context.getTimer(), key).join();
                oldMappings.put(key, value);
            }
        }

        globalScope.setWindow(10000L).join();

        try (FDBRecordContext context = database.openContext()) {
            for (int i = 0; i < 20; i++) {
                Long value = globalScope.resolve(context.getTimer(), "new-resolved-" + i).join();
                assertThat("resolved value is larger than the set window", value, greaterThanOrEqualTo(10000L));
            }

            for (Map.Entry<String, Long> entry : oldMappings.entrySet()) {
                Long value = globalScope.resolve(context.getTimer(), entry.getKey()).join();
                assertThat("we can still read the old mappings", value, is(entry.getValue()));
            }
        }
    }

    // Protected methods
    @Test
    public void testReadCreateExists() {
        ResolverResult value;
        try (FDBRecordContext context = database.openContext()) {
            value = globalScope.create(context, "a-string").join();

            assertThat("we see the value exists", globalScope.read(context, "a-string").join().isPresent(), is(true));
            assertThat("we see other values don't exist", globalScope.read(context, "something-else").join().isPresent(), is(false));
            assertThat("we can read the value", globalScope.read(context, "a-string").join(), is(Optional.of(value)));
            assertThat("we get nothing for other values", globalScope.read(context, "something-else").join(), is(Optional.empty()));
        }
    }

    protected void wipeFDB() {
        database.run((context -> {
            context.ensureActive().clear(new Range(new byte[] {(byte)0x00}, new byte[] {(byte)0xFF}));
            return null;
        }));
        database.clearCaches();
    }

}
