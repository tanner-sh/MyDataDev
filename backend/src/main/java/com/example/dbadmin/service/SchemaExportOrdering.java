package com.example.dbadmin.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 对象定义导出的排序：被依赖的对象排在前面。
 *
 * <p>纯逻辑，不碰数据库。依赖边由调用方给（表的外键、数据库依赖字典、源码里出现的对象名），
 * 这里只做两件事：拓扑排序，以及在没有边约束时按类型给一个「通常能跑通」的默认顺序 ——
 * 序列 → 表 → 函数 → 视图 → 物化视图 → 存储过程 → 触发器。默认顺序本身就挡住了大多数情况
 * （视图引用表、触发器挂在表上），边只需要处理同类型之间的先后（视图引用视图、外键）。</p>
 *
 * <p>有环时不能假装排好了：环上的对象按默认顺序输出，并把环原样报出来。外键互相引用是最常见的
 * 环，执行时要先建表、后补约束，那是用户要知道的事，而不是悄悄生成一份跑不通的脚本。
 * 自引用（表的外键指向自己）不算环，建表语句本身就能处理。</p>
 */
public final class SchemaExportOrdering {
    private static final List<String> KIND_ORDER = List.of(
            "SEQUENCE", "TABLE", "FUNCTION", "VIEW", "MATERIALIZED_VIEW", "PROCEDURE", "TRIGGER");

    private SchemaExportOrdering() {
    }

    /** 一个待导出的对象。key 由调用方定，在一次排序内唯一。 */
    public record Node(String key, String kind, String name) {
    }

    /** dependent 依赖 dependency：dependency 必须先建。 */
    public record Edge(String dependent, String dependency) {
    }

    public record Result(List<Node> ordered, Map<String, List<String>> dependsOn, List<List<String>> cycles) {
    }

    public static int kindRank(String kind) {
        int index = KIND_ORDER.indexOf(kind == null ? "" : kind.toUpperCase(Locale.ROOT));
        return index < 0 ? KIND_ORDER.size() : index;
    }

    public static Result order(List<Node> nodes, List<Edge> edges) {
        Map<String, Node> byKey = new HashMap<>();
        Map<String, Integer> position = new HashMap<>();
        for (Node node : nodes) {
            if (byKey.putIfAbsent(node.key(), node) == null) position.put(node.key(), position.size());
        }
        Map<String, Set<String>> dependencies = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (Node node : byKey.values()) {
            dependencies.put(node.key(), new LinkedHashSet<>());
            dependents.put(node.key(), new LinkedHashSet<>());
        }
        for (Edge edge : edges) {
            if (!byKey.containsKey(edge.dependent()) || !byKey.containsKey(edge.dependency())) continue;
            if (edge.dependent().equals(edge.dependency())) continue;
            dependencies.get(edge.dependent()).add(edge.dependency());
            dependents.get(edge.dependency()).add(edge.dependent());
        }

        Comparator<String> priority = Comparator
                .comparingInt((String key) -> kindRank(byKey.get(key).kind()))
                .thenComparing(key -> byKey.get(key).name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(position::get);
        Map<String, Integer> remaining = new HashMap<>();
        PriorityQueue<String> ready = new PriorityQueue<>(priority);
        for (Node node : byKey.values()) {
            int count = dependencies.get(node.key()).size();
            remaining.put(node.key(), count);
            if (count == 0) ready.add(node.key());
        }
        List<Node> ordered = new ArrayList<>(byKey.size());
        Set<String> emitted = new LinkedHashSet<>();
        while (emitted.size() < byKey.size()) {
            String next = ready.poll();
            if (next == null) {
                // 剩下的都在环上（或依赖环上的对象）：按默认顺序挑一个先放出来，排序继续往下走。
                next = byKey.keySet().stream().filter(key -> !emitted.contains(key)).min(priority).orElseThrow();
            } else if (emitted.contains(next)) {
                continue;
            }
            emitted.add(next);
            ordered.add(byKey.get(next));
            for (String dependent : dependents.get(next)) {
                if (emitted.contains(dependent)) continue;
                int count = remaining.merge(dependent, -1, Integer::sum);
                if (count == 0) ready.add(dependent);
            }
        }

        Map<String, List<String>> dependsOn = new HashMap<>();
        for (Node node : ordered) {
            dependsOn.put(node.key(), dependencies.get(node.key()).stream()
                    .sorted(Comparator.comparingInt(ordered::indexOf))
                    .toList());
        }
        return new Result(List.copyOf(ordered), dependsOn, cycles(ordered, dependencies, byKey));
    }

    /** Tarjan 强连通分量；只报两个及以上对象组成的分量，每个分量按输出顺序列名字。 */
    private static List<List<String>> cycles(List<Node> ordered, Map<String, Set<String>> dependencies, Map<String, Node> byKey) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new LinkedHashSet<>();
        List<List<String>> components = new ArrayList<>();
        int[] counter = {0};
        for (Node node : ordered) {
            if (!index.containsKey(node.key())) strongConnect(node.key(), dependencies, index, low, stack, onStack, components, counter);
        }
        Map<String, Integer> outputPosition = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) outputPosition.put(ordered.get(i).key(), i);
        return components.stream()
                .filter(component -> component.size() > 1)
                .map(component -> component.stream()
                        .sorted(Comparator.comparingInt(outputPosition::get))
                        .map(key -> byKey.get(key).name())
                        .toList())
                .sorted(Comparator.comparing(component -> component.get(0), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static void strongConnect(String key, Map<String, Set<String>> dependencies, Map<String, Integer> index,
                                      Map<String, Integer> low, Deque<String> stack, Set<String> onStack,
                                      List<List<String>> components, int[] counter) {
        // 迭代实现：几百个对象的依赖链递归下去不会爆栈，但没理由冒这个险。
        Deque<Map.Entry<String, java.util.Iterator<String>>> work = new ArrayDeque<>();
        index.put(key, counter[0]);
        low.put(key, counter[0]++);
        stack.push(key);
        onStack.add(key);
        work.push(Map.entry(key, dependencies.get(key).iterator()));
        while (!work.isEmpty()) {
            var frame = work.peek();
            String current = frame.getKey();
            if (frame.getValue().hasNext()) {
                String next = frame.getValue().next();
                if (!index.containsKey(next)) {
                    index.put(next, counter[0]);
                    low.put(next, counter[0]++);
                    stack.push(next);
                    onStack.add(next);
                    work.push(Map.entry(next, dependencies.get(next).iterator()));
                } else if (onStack.contains(next)) {
                    low.put(current, Math.min(low.get(current), index.get(next)));
                }
                continue;
            }
            work.pop();
            if (!work.isEmpty()) {
                String parent = work.peek().getKey();
                low.put(parent, Math.min(low.get(parent), low.get(current)));
            }
            if (low.get(current).equals(index.get(current))) {
                List<String> component = new ArrayList<>();
                String member;
                do {
                    member = stack.pop();
                    onStack.remove(member);
                    component.add(member);
                } while (!member.equals(current));
                components.add(component);
            }
        }
    }
}
