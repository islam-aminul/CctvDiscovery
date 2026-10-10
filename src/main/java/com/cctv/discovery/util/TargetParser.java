package com.cctv.discovery.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Parses free-form scan targets into a de-duplicated set of IPv4 addresses.
 * <p>
 * Accepted tokens (separated by commas, semicolons, whitespace or new lines):
 * <ul>
 *   <li>single address: {@code 192.168.1.10}</li>
 *   <li>full range: {@code 192.168.1.10-192.168.1.50}</li>
 *   <li>short range: {@code 192.168.1.10-50}</li>
 *   <li>CIDR: {@code 192.168.1.0/24} (network and broadcast excluded for prefixes up to /30)</li>
 * </ul>
 * Overlapping entries are merged, so every address is scanned once. Addresses
 * are produced lazily; nothing proportional to the range size is allocated.
 */
public final class TargetParser {

    /** Inclusive address interval. */
    public record Interval(long start, long end) {
        public long size() {
            return end - start + 1;
        }
    }

    /** Result of parsing: merged intervals plus tokens that were rejected. */
    public record Targets(List<Interval> intervals, List<String> invalidTokens) implements Iterable<String> {
        public long count() {
            long total = 0;
            for (Interval i : intervals) {
                total += i.size();
            }
            return total;
        }

        public boolean isEmpty() {
            return intervals.isEmpty();
        }

        public boolean contains(String ip) {
            if (!NetworkUtils.isValidIP(ip)) {
                return false;
            }
            long v = NetworkUtils.ipToLong(ip);
            for (Interval i : intervals) {
                if (v >= i.start && v <= i.end) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Iterator<String> iterator() {
            return new Iterator<>() {
                private int index;
                private long next = intervals.isEmpty() ? 0 : intervals.getFirst().start;

                @Override
                public boolean hasNext() {
                    return index < intervals.size();
                }

                @Override
                public String next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    String ip = NetworkUtils.longToIp(next);
                    if (next == intervals.get(index).end) {
                        index++;
                        if (index < intervals.size()) {
                            next = intervals.get(index).start;
                        }
                    } else {
                        next++;
                    }
                    return ip;
                }
            };
        }

        /** Materialise up to {@code limit} addresses. */
        public List<String> toList(int limit) {
            List<String> list = new ArrayList<>();
            for (String ip : this) {
                if (list.size() >= limit) {
                    break;
                }
                list.add(ip);
            }
            return list;
        }
    }

    private TargetParser() {
    }

    public static Targets parse(String text) {
        List<Interval> raw = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        if (text != null) {
            for (String token : text.split("[,;\\s]+")) {
                String t = token.trim();
                if (t.isEmpty()) {
                    continue;
                }
                Interval interval = parseToken(t);
                if (interval == null) {
                    invalid.add(t);
                } else {
                    raw.add(interval);
                }
            }
        }
        return new Targets(merge(raw), Collections.unmodifiableList(invalid));
    }

    /** Parse one token; null if invalid. */
    public static Interval parseToken(String token) {
        try {
            int slash = token.indexOf('/');
            if (slash > 0) {
                String base = token.substring(0, slash);
                int prefix = Integer.parseInt(token.substring(slash + 1));
                if (!NetworkUtils.isValidIP(base) || prefix < 0 || prefix > 32) {
                    return null;
                }
                long mask = NetworkUtils.prefixMask(prefix);
                long network = NetworkUtils.ipToLong(base) & mask;
                long broadcast = network | (~mask & 0xFFFFFFFFL);
                return prefix >= 31 ? new Interval(network, broadcast) : new Interval(network + 1, broadcast - 1);
            }
            int dash = token.indexOf('-');
            if (dash > 0) {
                String left = token.substring(0, dash).trim();
                String right = token.substring(dash + 1).trim();
                if (!NetworkUtils.isValidIP(left)) {
                    return null;
                }
                long start = NetworkUtils.ipToLong(left);
                long end;
                if (NetworkUtils.isValidIP(right)) {
                    end = NetworkUtils.ipToLong(right);
                } else {
                    int lastOctet = Integer.parseInt(right);
                    if (lastOctet < 0 || lastOctet > 255) {
                        return null;
                    }
                    end = (start & 0xFFFFFF00L) | lastOctet;
                }
                return start <= end ? new Interval(start, end) : null;
            }
            if (NetworkUtils.isValidIP(token)) {
                long v = NetworkUtils.ipToLong(token);
                return new Interval(v, v);
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    static List<Interval> merge(List<Interval> intervals) {
        if (intervals.isEmpty()) {
            return List.of();
        }
        List<Interval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparingLong(Interval::start));
        List<Interval> merged = new ArrayList<>();
        Interval current = sorted.getFirst();
        for (int i = 1; i < sorted.size(); i++) {
            Interval next = sorted.get(i);
            if (next.start <= current.end + 1) {
                current = new Interval(current.start, Math.max(current.end, next.end));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return Collections.unmodifiableList(merged);
    }
}
