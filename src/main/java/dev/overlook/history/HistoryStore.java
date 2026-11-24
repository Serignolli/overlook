package dev.overlook.history;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.overlook.vercel.model.Granularity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Histórico local, opcional: guarda os buckets fechados que o app já buscou na Vercel, para
 * continuar mostrando dias que saíram da janela do plano.
 *
 * <p>Um arquivo JSON por escopo em {@code <dados>/history/}. Cada série guarda os intervalos
 * cobertos (o que já foi consultado) e só os valores diferentes de zero: dentro de um intervalo
 * coberto, bucket ausente é zero; fora dele, é desconhecido. Sem {@code dir}, fica só em memória
 * (modo demo).
 */
public final class HistoryStore {

    private static final Logger log = LoggerFactory.getLogger(HistoryStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Pattern SAFE_SCOPE = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final String ALL_PROJECTS = "*";

    /**
     * Identifica uma série.
     *
     * @param project {@code null} para a consulta de todos os projetos agrupada por projeto
     */
    public record SeriesKey(String metric, String aggregation, boolean production, Granularity granularity,
                            String project) {
        String id() {
            return String.join("|", metric, aggregation == null ? "count" : aggregation, production ? "prod" : "all",
                    granularity.flag(), project == null ? ALL_PROJECTS : project);
        }
    }

    public record Interval(Instant from, Instant to) {
    }

    /**
     * Trecho lido do histórico.
     *
     * @param values grupo (ID do projeto, ou vazio sem agrupamento) para valor por início de bucket
     * @param covered intervalos cobertos dentro do trecho pedido, em ordem
     */
    public record Slice(Map<String, Map<Instant, Double>> values, List<Interval> covered) {
        public boolean covers(Instant from, Instant to) {
            return !from.isBefore(to) || covered.stream().anyMatch(i -> !i.from().isAfter(from) && !i.to().isBefore(to));
        }

        public boolean isEmpty() {
            return covered.isEmpty();
        }
    }

    public record ScopeStats(String scope, Instant oldest, Instant newest) {
    }

    public record Stats(boolean enabled, long bytes, List<ScopeStats> scopes) {
    }

    /** Formato do arquivo. Campos públicos para o Jackson. */
    static final class ScopeFile {
        public int version = 1;
        public Map<String, SeriesData> series = new LinkedHashMap<>();
    }

    static final class SeriesData {
        /** Pares [início, fim) em segundos desde a época, ordenados e sem sobreposição. */
        public List<long[]> covered = new ArrayList<>();
        /** Grupo → (segundos desde a época → valor). */
        public Map<String, TreeMap<Long, Double>> values = new LinkedHashMap<>();
    }

    private final Path dir;
    private final BooleanSupplier enabled;
    private final Map<String, ScopeFile> loaded = new HashMap<>();

    public HistoryStore(Path dir, BooleanSupplier enabled) {
        this.dir = dir;
        this.enabled = enabled;
    }

    public static HistoryStore inMemory(BooleanSupplier enabled) {
        return new HistoryStore(null, enabled);
    }

    public boolean enabled() {
        return enabled.getAsBoolean();
    }

    /**
     * Grava os valores de {@code [from, to)}, substituindo o que havia nesse trecho.
     * Não faz nada com o histórico desativado.
     */
    public synchronized void record(String scope, SeriesKey key, Instant from, Instant to,
                                    Map<String, Map<Instant, Double>> values) {
        if (!enabled() || !from.isBefore(to) || !SAFE_SCOPE.matcher(scope).matches()) {
            return;
        }
        ScopeFile file = file(scope);
        SeriesData series = file.series.computeIfAbsent(key.id(), k -> new SeriesData());
        long f = from.getEpochSecond();
        long t = to.getEpochSecond();
        for (TreeMap<Long, Double> byTime : series.values.values()) {
            byTime.subMap(f, t).clear();
        }
        values.forEach((group, points) -> points.forEach((time, value) -> {
            long s = time.getEpochSecond();
            if (s >= f && s < t && value != null && value != 0) {
                series.values.computeIfAbsent(group == null ? "" : group, g -> new TreeMap<>()).put(s, value);
            }
        }));
        series.values.values().removeIf(TreeMap::isEmpty);
        series.covered = merge(series.covered, new long[] {f, t});
        save(scope, file);
    }

    /** Lê {@code [from, to)}. Com o histórico desativado, devolve um trecho vazio. */
    public synchronized Slice read(String scope, SeriesKey key, Instant from, Instant to) {
        if (!enabled() || !from.isBefore(to) || !SAFE_SCOPE.matcher(scope).matches()) {
            return new Slice(Map.of(), List.of());
        }
        SeriesData series = file(scope).series.get(key.id());
        if (series == null) {
            return new Slice(Map.of(), List.of());
        }
        long f = from.getEpochSecond();
        long t = to.getEpochSecond();
        Map<String, Map<Instant, Double>> out = new LinkedHashMap<>();
        series.values.forEach((group, byTime) -> {
            Map<Instant, Double> points = new LinkedHashMap<>();
            byTime.subMap(f, t).forEach((s, v) -> points.put(Instant.ofEpochSecond(s), v));
            if (!points.isEmpty()) {
                out.put(group, points);
            }
        });
        List<Interval> covered = new ArrayList<>();
        for (long[] c : series.covered) {
            long a = Math.max(c[0], f);
            long b = Math.min(c[1], t);
            if (a < b) {
                covered.add(new Interval(Instant.ofEpochSecond(a), Instant.ofEpochSecond(b)));
            }
        }
        return new Slice(out, covered);
    }

    /** Apaga tudo o que for anterior a {@code cutoff}, em todos os escopos. */
    public synchronized void prune(Instant cutoff) {
        long c = cutoff.getEpochSecond();
        for (String scope : scopesOnDisk()) {
            ScopeFile file = file(scope);
            boolean changed = false;
            for (SeriesData series : file.series.values()) {
                for (TreeMap<Long, Double> byTime : series.values.values()) {
                    if (!byTime.headMap(c).isEmpty()) {
                        byTime.headMap(c).clear();
                        changed = true;
                    }
                }
                series.values.values().removeIf(TreeMap::isEmpty);
                List<long[]> kept = new ArrayList<>();
                for (long[] i : series.covered) {
                    if (i[1] <= c) {
                        changed = true;
                    } else if (i[0] < c) {
                        kept.add(new long[] {c, i[1]});
                        changed = true;
                    } else {
                        kept.add(i);
                    }
                }
                series.covered = kept;
            }
            changed |= file.series.values().removeIf(s -> s.covered.isEmpty());
            if (changed) {
                save(scope, file);
            }
        }
    }

    /** Apaga o histórico inteiro, do disco e da memória. */
    public synchronized void clear() {
        loaded.clear();
        if (dir == null || Files.notExists(dir)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : files) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível apagar o histórico em " + dir, e);
        }
    }

    public synchronized Stats stats() {
        List<ScopeStats> scopes = new ArrayList<>();
        long bytes = 0;
        for (String scope : scopesOnDisk()) {
            ScopeFile file = file(scope);
            long oldest = Long.MAX_VALUE;
            long newest = Long.MIN_VALUE;
            for (SeriesData s : file.series.values()) {
                for (long[] i : s.covered) {
                    oldest = Math.min(oldest, i[0]);
                    newest = Math.max(newest, i[1]);
                }
            }
            if (oldest != Long.MAX_VALUE) {
                scopes.add(new ScopeStats(scope, Instant.ofEpochSecond(oldest), Instant.ofEpochSecond(newest)));
            }
            if (dir != null) {
                try {
                    Path p = dir.resolve(scope + ".json");
                    bytes += Files.exists(p) ? Files.size(p) : 0;
                } catch (IOException e) {
                    log.warn("Não foi possível medir {}: {}", scope, e.getMessage());
                }
            }
        }
        scopes.sort(Comparator.comparing(ScopeStats::scope));
        return new Stats(enabled(), bytes, scopes);
    }

    /** Início mais antigo guardado para o escopo, em qualquer série, ou {@code null}. */
    public synchronized Instant oldest(String scope) {
        return stats().scopes().stream().filter(s -> s.scope().equals(scope)).map(ScopeStats::oldest)
                .findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- arquivo

    private List<String> scopesOnDisk() {
        List<String> scopes = new ArrayList<>(loaded.keySet());
        if (dir != null && Files.isDirectory(dir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
                for (Path p : files) {
                    String name = p.getFileName().toString();
                    String scope = name.substring(0, name.length() - ".json".length());
                    if (SAFE_SCOPE.matcher(scope).matches() && !scopes.contains(scope)) {
                        scopes.add(scope);
                    }
                }
            } catch (IOException e) {
                log.warn("Não foi possível listar o histórico: {}", e.getMessage());
            }
        }
        return scopes;
    }

    private ScopeFile file(String scope) {
        return loaded.computeIfAbsent(scope, s -> {
            if (dir == null) {
                return new ScopeFile();
            }
            Path p = dir.resolve(s + ".json");
            if (Files.notExists(p)) {
                return new ScopeFile();
            }
            try {
                ScopeFile f = MAPPER.readValue(p.toFile(), ScopeFile.class);
                return f.series == null ? new ScopeFile() : f;
            } catch (IOException e) {
                // arquivo corrompido: guarda de lado e recomeça, em vez de travar o painel
                log.warn("Histórico ilegível em {}: {}. Guardado como .corrompido", p, e.getMessage());
                try {
                    Files.move(p, p.resolveSibling(p.getFileName() + ".corrompido"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // segue com um arquivo novo
                }
                return new ScopeFile();
            }
        });
    }

    private void save(String scope, ScopeFile file) {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(scope + ".json");
            Path tmp = dir.resolve(scope + ".json.tmp");
            MAPPER.writeValue(tmp.toFile(), file);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Não foi possível gravar o histórico de {}: {}", scope, e.getMessage());
        }
    }

    static List<long[]> merge(List<long[]> intervals, long[] add) {
        List<long[]> all = new ArrayList<>(intervals);
        all.add(add);
        all.sort(Comparator.comparingLong(i -> i[0]));
        List<long[]> out = new ArrayList<>();
        for (long[] i : all) {
            if (!out.isEmpty() && i[0] <= out.get(out.size() - 1)[1]) {
                long[] last = out.get(out.size() - 1);
                last[1] = Math.max(last[1], i[1]);
            } else {
                out.add(new long[] {i[0], i[1]});
            }
        }
        return out;
    }
}
