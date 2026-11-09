package dev.overlook.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AggregationRulesTest {

    @Test
    void classifiesByAggregation() {
        assertThat(AggregationRules.classOf(null)).isEqualTo(AggClass.ADDITIVE);
        assertThat(AggregationRules.classOf("sum")).isEqualTo(AggClass.ADDITIVE);
        assertThat(AggregationRules.classOf("unique/visitorId")).isEqualTo(AggClass.UNIQUE);
    }

    @Test
    void additiveSums() {
        assertThat(AggregationRules.periodTotal(AggClass.ADDITIVE, List.of(1d, 2d, 3d))).hasValue(6d);
        assertThat(AggregationRules.sumAcrossProjects(AggClass.ADDITIVE, List.of(10d, 5d))).isEqualTo(15d);
    }

    @Test
    void uniqueNeverSums() {
        assertThat(AggregationRules.periodTotal(AggClass.UNIQUE, List.of(1d, 2d))).isEmpty();
        assertThat(AggregationRules.summableAcrossBuckets(AggClass.UNIQUE)).isFalse();
        assertThat(AggregationRules.summableAcrossProjects(AggClass.UNIQUE)).isFalse();
        assertThatThrownBy(() -> AggregationRules.sumAcrossProjects(AggClass.UNIQUE, List.of(1d)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void projectSumOfUniquesIsAlwaysLabeled() {
        AggregationRules.LabeledSum s = AggregationRules.projectSumOfUniques(List.of(40d, 5d));

        assertThat(s.value()).isEqualTo(45d);
        assertThat(s.label()).isEqualTo("Soma dos visitantes por projeto").doesNotContainIgnoringCase("únicos totais");
        assertThat(s.caveat()).contains("mais de uma vez");
    }

    @Test
    void changeNeedsBase() {
        assertThat(AggregationRules.change(150, 100)).isEqualTo(0.5);
        assertThat(AggregationRules.change(10, 0)).isNull();
    }
}
