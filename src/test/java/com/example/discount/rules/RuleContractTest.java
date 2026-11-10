package com.example.discount.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.discount.support.CompatibilityClient;
import com.example.discount.support.TestSupportConfig;
import com.example.discount.support.CompatibilityClient.Verdict;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * One test per configured rule: send a request that makes the rule fire, and check the engine did
 * what the rule row says.
 *
 * <p>There is no list of scenarios to maintain. Add a rule in the browser editor and a case
 * appears here; change a winner and the expectation changes with it. A rule that cannot be made
 * to fire at all fails, which is how a dead rule gets found.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSupportConfig.class)
class RuleContractTest {

    @Autowired
    private CompatibilityClient client;

    @TestFactory
    List<DynamicTest> everyConfiguredRuleBehavesAsDeclared() {
        RuleTables tables = client.rules();
        List<RuleProbes.Probe> probes = new RuleProbes(tables).all();

        assertThat(probes)
                .as("the rule tables are empty, so this suite would assert nothing")
                .isNotEmpty();

        List<DynamicTest> tests = new ArrayList<>();
        for (RuleProbes.Probe probe : probes) {
            tests.add(DynamicTest.dynamicTest(probe.name(), () -> check(probe)));
        }
        return tests;
    }

    private void check(RuleProbes.Probe probe) {
        if (probe.isUnreachable()) {
            fail("Rule " + probe.ruleId() + " is dead: it " + probe.expect().unreachableReason());
        }

        Verdict verdict = client.check(probe.request());
        Map<String, Object> conflict = verdict.conflictFor(probe.expect().ruleId());

        assertThat(conflict)
                .as("%s should have fired for %s but the verdict reported no conflict for it.%n"
                                + "request : %s%nconflicts: %s",
                        probe.expect().ruleId(), probe.name(), verdict.requestJson(), verdict.conflictIds())
                .isNotNull();

        assertThat(conflict.get("ruleType"))
                .as("%s ruleType", probe.ruleId())
                .isEqualTo(probe.expect().ruleType());

        if (probe.expect().exemptedItemId() != null) {
            assertThat(stringsOf(conflict.get("exemptedItemIds")))
                    .as("%s should have rescued item %s from %s.%nconflict: %s",
                            probe.ruleId(), probe.expect().exemptedItemId(),
                            probe.expect().ruleId(), conflict)
                    .contains(probe.expect().exemptedItemId());
            assertThat(stringsOf(conflict.get("exemptedByRuleIds")))
                    .as("%s should be credited as the rule that rescued it.%nconflict: %s",
                            probe.ruleId(), conflict)
                    .contains(probe.expect().exemptedByRuleId());
            return;
        }

        assertThat(conflict.get("resolution"))
                .as("%s resolution.%nconflict: %s", probe.ruleId(), conflict)
                .isEqualTo(probe.expect().resolution());
        assertThat(conflict.get("winnerItemId"))
                .as("%s winner.%nconflict: %s", probe.ruleId(), conflict)
                .isEqualTo(probe.expect().winnerItemId());
        assertThat(stringsOf(conflict.get("loserItemIds")))
                .as("%s losers.%nconflict: %s", probe.ruleId(), conflict)
                .containsExactlyInAnyOrderElementsOf(probe.expect().loserItemIds());

        // A rule that resolves a conflict has to actually cost the loser its place, otherwise
        // the conflict is reported but nothing is enforced.
        assertThat(verdict.allowedIds())
                .as("%s named %s the loser, so it must not be in allowed.%nverdict: %s",
                        probe.ruleId(), probe.expect().loserItemIds(), verdict.json())
                .doesNotContainAnyElementsOf(probe.expect().loserItemIds());
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringsOf(Object value) {
        return value == null ? List.of() : (List<String>) value;
    }
}
