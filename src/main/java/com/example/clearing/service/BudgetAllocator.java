package com.example.clearing.service;

import com.example.clearing.config.ClearingProperties;
import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.DepHealth;
import com.example.clearing.domain.DependencyNode;
import com.example.clearing.domain.QuotaGrant;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.domain.TokenBucket;
import com.example.clearing.repo.ClearingTaskRepository;
import com.example.clearing.repo.DependencyNodeRepository;
import com.example.clearing.repo.QuotaGrantRepository;
import com.example.clearing.repo.TokenBucketRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预算分配器（协调器）：不直接持有令牌，而是
 *  1. 按依赖健康度折算各本地桶的补充速率（降级减半、DOWN 置零）；
 *  2. 把周期预算按 依赖 × 来源 × 金额优先级 注水分配，写入配额证据（QuotaGrant）供审计。
 * 故障隔离：某依赖 DOWN 只收紧其自身队列，其他依赖的桶照常补充。
 */
@Service
public class BudgetAllocator {

    private final TokenBucketRepository bucketRepository;
    private final ClearingTaskRepository taskRepository;
    private final DependencyNodeRepository nodeRepository;
    private final QuotaGrantRepository quotaRepository;
    private final ClearingProperties properties;

    public BudgetAllocator(TokenBucketRepository bucketRepository,
            ClearingTaskRepository taskRepository,
            DependencyNodeRepository nodeRepository,
            QuotaGrantRepository quotaRepository,
            ClearingProperties properties) {
        this.bucketRepository = bucketRepository;
        this.taskRepository = taskRepository;
        this.nodeRepository = nodeRepository;
        this.quotaRepository = quotaRepository;
        this.properties = properties;
    }

    @Transactional
    public List<QuotaGrant> allocate() {
        List<QuotaGrant> result = new ArrayList<>();
        String period = "QP" + Instant.now().truncatedTo(ChronoUnit.MINUTES);
        int ticksPerPeriod = properties.getCoordinator().getTicksPerPeriod();

        for (TokenBucket bucket : bucketRepository.findAll()) {
            String code = bucket.getDependencyCode();
            DepHealth health = nodeRepository.findById(code)
                    .map(DependencyNode::getHealth)
                    .orElse(DepHealth.HEALTHY);
            ClearingProperties.BucketSpec spec = properties.getBuckets().get(code);
            int baseRate = spec != null ? spec.getRefillPerTick() : bucket.getRefillPerTick();
            double factor = switch (health) {
                case HEALTHY -> 1.0;
                case DEGRADED -> 0.5;
                case DOWN -> 0.0;
            };
            int effectiveRate = (int) Math.floor(baseRate * factor);
            if (!bucket.isFrozen() && effectiveRate != bucket.getRefillPerTick()) {
                bucket.setRefillPerTick(effectiveRate);
                bucketRepository.save(bucket);
            }
            int periodBudget = effectiveRate * ticksPerPeriod;

            List<ClearingTask> queue = taskRepository.findByStatusInAndDependencyCode(
                    List.of(TaskStatus.READY, TaskStatus.RETRY_SCHEDULED), code);
            if (queue.isEmpty()) {
                continue;
            }

            // 需求分组：来源 × 金额优先级带
            Map<String, Integer> demandByGroup = new TreeMap<>();
            for (ClearingTask t : queue) {
                String key = t.getSourceType() + "|" + TaskRegistrar.bandOf(t.getPriority());
                demandByGroup.merge(key, 1, Integer::sum);
            }

            // 注水分配：金额优先级 HIGH > MEDIUM > LOW，同优先级按来源名字典序（可审计的确定顺序）
            List<String> groups = new ArrayList<>(demandByGroup.keySet());
            groups.sort((a, b) -> {
                int bandCmp = Integer.compare(bandRank(b.split("\\|")[1]), bandRank(a.split("\\|")[1]));
                return bandCmp != 0 ? bandCmp : a.compareTo(b);
            });

            int remaining = periodBudget;
            for (String group : groups) {
                String[] parts = group.split("\\|");
                int demand = demandByGroup.get(group);
                int granted = Math.max(0, Math.min(demand, remaining));
                remaining -= granted;

                QuotaGrant grant = new QuotaGrant();
                grant.setDependencyCode(code);
                grant.setSourceType(parts[0]);
                grant.setPriorityBand(parts[1]);
                grant.setDemand(demand);
                grant.setGranted(granted);
                grant.setPeriodLabel(period);
                grant.setReason(switch (health) {
                    case DOWN -> "依赖 DOWN：本地令牌桶冻结，周期配额置零；故障隔离仅收紧本依赖队列，不影响其他依赖";
                    case DEGRADED -> "依赖降级：周期预算减半（基数 " + baseRate + "/周期 → " + effectiveRate
                            + "/周期），按金额优先级注水分配";
                    default -> "按依赖周期预算 " + periodBudget + "（" + effectiveRate + "/周期 × " + ticksPerPeriod
                            + "），按来源与金额优先级注水分配";
                });
                quotaRepository.save(grant);
                result.add(grant);
            }
        }
        return result;
    }

    private static int bandRank(String band) {
        return switch (band) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }
}
