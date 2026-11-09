package dev.overlook.analytics;

import java.util.Collection;
import java.util.OptionalDouble;

/**
 * Decide o que pode ser somado entre buckets e entre projetos.
 *
 * <ul>
 *   <li>{@link AggClass#ADDITIVE} (page views, eventos): soma em qualquer direção.</li>
 *   <li>{@link AggClass#UNIQUE} (visitantes): nunca soma. O total de um período vem de uma
 *       consulta à Vercel com o período inteiro; entre projetos só existe a "soma dos visitantes
 *       por projeto", sempre rotulada como tal.</li>
 * </ul>
 */
public final class AggregationRules {

    public static final String PROJECT_SUM_LABEL = "Soma dos visitantes por projeto";
    public static final String PROJECT_SUM_CAVEAT =
            "A mesma pessoa pode contar mais de uma vez se visitou mais de um projeto.";

    private AggregationRules() {
    }

    public static AggClass classOf(String aggregation) {
        return aggregation != null && aggregation.startsWith("unique") ? AggClass.UNIQUE : AggClass.ADDITIVE;
    }

    public static boolean summableAcrossBuckets(AggClass c) {
        return c == AggClass.ADDITIVE;
    }

    public static boolean summableAcrossProjects(AggClass c) {
        return c == AggClass.ADDITIVE;
    }

    /** Total de um período a partir dos buckets; vazio quando a classe não permite somar. */
    public static OptionalDouble periodTotal(AggClass c, Collection<Double> buckets) {
        if (!summableAcrossBuckets(c)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(buckets.stream().mapToDouble(Double::doubleValue).sum());
    }

    /** Soma entre projetos. Para {@code UNIQUE}, use {@link #projectSumOfUniques}. */
    public static double sumAcrossProjects(AggClass c, Collection<Double> perProject) {
        if (!summableAcrossProjects(c)) {
            throw new IllegalStateException("Métricas UNIQUE não somam entre projetos");
        }
        return perProject.stream().mapToDouble(Double::doubleValue).sum();
    }

    /** Valor explicitamente rotulado como soma por projeto, nunca como únicos totais. */
    public static LabeledSum projectSumOfUniques(Collection<Double> perProject) {
        double sum = perProject.stream().mapToDouble(Double::doubleValue).sum();
        return new LabeledSum(sum, PROJECT_SUM_LABEL, PROJECT_SUM_CAVEAT);
    }

    public record LabeledSum(double value, String label, String caveat) {
    }

    /** Variação relativa, ou {@code null} sem base de comparação. */
    public static Double change(double current, double previous) {
        if (previous <= 0) {
            return null;
        }
        return (current - previous) / previous;
    }
}
