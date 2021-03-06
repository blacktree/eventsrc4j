package eventsrc4j.memory;

import eventsrc4j.Event;
import eventsrc4j.Events;
import eventsrc4j.Sequence;
import eventsrc4j.SequenceQuery;
import eventsrc4j.Snapshot;
import eventsrc4j.SnapshotStoreMode;
import eventsrc4j.io.IO;
import eventsrc4j.io.SnapshotStorage;
import eventsrc4j.io.SnapshotStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Stream;

import static eventsrc4j.SequenceQueries.caseOf;
import static eventsrc4j.SnapshotStoreModes.caseOf;
import static eventsrc4j.Snapshots.NoSnapshot;
import static eventsrc4j.io.IO.io;
import static eventsrc4j.memory.GlobalSeqs.seq;
import static fj.data.Java8.F_Function;
import static java.util.Optional.ofNullable;

public final class MemorySnapshotStorage<K, S, V> implements SnapshotStorage<K, S, V> {

    private final ConcurrentMap<K, SnapshotStream<S, V>> snapshotStreams = new ConcurrentHashMap<>();

    private final Sequence<S> sequence;

    public MemorySnapshotStorage(Sequence<S> sequence) {
        this.sequence = sequence;
    }

    @Override
    public SnapshotStream<S, V> snapshots(K key) {
        return snapshotStreams.computeIfAbsent(key, k -> new MemorySnapshotStream<>(sequence));
    }

    private static final class MemorySnapshotStream<S, V> implements SnapshotStream<S, V> {

        private final ConcurrentSkipListMap<S, Snapshot<S, V>> snapshotsMap;

        public MemorySnapshotStream(Sequence<S> sequence) {
            this.snapshotsMap = new ConcurrentSkipListMap<>(sequence);
        }

        @Override
        public IO<Snapshot<S, V>> get(SequenceQuery<S> sequence) {
            return caseOf(sequence)
                    .Before(s -> io(() -> entryToSnapshot(snapshotsMap.lowerEntry(s))))
                    .Earliest_(() -> entryToSnapshot(snapshotsMap.firstEntry()))
                    .Latest_(() -> entryToSnapshot(snapshotsMap.lastEntry()));
        }

        @Override
        public IO<Snapshot<S, V>> put(Snapshot<S, V> snapshot, SnapshotStoreMode mode) {
            return snapshot.seq().map((s) -> caseOf(mode)
                    .Epoch_(io(() -> {
                        snapshotsMap.put(s, snapshot);
                        snapshotsMap.headMap(s).clear();
                        return snapshot;
                    }))
                    .Cache_(() -> {
                        snapshotsMap.put(s, snapshot);
                        return snapshot;
                    }))
                    .orSome(() -> snapshot);
        }


    }


    private static <S, V> Snapshot<S, V> entryToSnapshot(Map.Entry<S, Snapshot<S, V>> e) {
        return ofNullable(e).map(Map.Entry::getValue).orElse(NoSnapshot());
    }

    private static <K, S, E> Stream<Event<K, GlobalSeq<S>, E>> flattenGlobalStream(
            ConcurrentNavigableMap<S, List<Event<K, S, E>>> eventMap) {
        return eventMap
                .entrySet()
                .stream()
                .flatMap(entry -> entry.getValue()
                        .stream()
                        .map(F_Function(Events.modSeq(s -> seq(entry.getKey(), s)))));
    }
}
