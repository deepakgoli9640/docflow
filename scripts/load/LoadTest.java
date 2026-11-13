// Same scenario as payments.js (k6), with only a JDK: hospitals create payments and read them back through
// the API gateway at a fixed arrival rate (open model), then print latency percentiles per request.
//
//   java scripts/load/LoadTest.java                            # 40 iterations/s for 60 s, 5 hospitals
//   java scripts/load/LoadTest.java --rate 80 --duration 120 --merchants 10 [--gateway http://localhost:8080]
//
// Reads HPG_OPS_CLIENT_SECRET from deploy/env/.env (run from the repository root). Needs the stack with the
// SIMULATED provider. Each hospital is limited to 50 requests/s by the gateway, so load is spread over hospitals.
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoadTest {

    static String gateway = "http://localhost:8080";
    static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    record Sample(long micros, int status) {
    }

    static final Map<String, ConcurrentLinkedQueue<Sample>> samples = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        int rate = 40, seconds = 60, merchants = 5;
        for (int i = 0; i < args.length; i += 2) {
            switch (args[i]) {
                case "--rate" -> rate = Integer.parseInt(args[i + 1]);
                case "--duration" -> seconds = Integer.parseInt(args[i + 1]);
                case "--merchants" -> merchants = Integer.parseInt(args[i + 1]);
                case "--gateway" -> gateway = args[i + 1];
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        String opsSecret = Files.readAllLines(Path.of("deploy/env/.env")).stream()
                .filter(l -> l.startsWith("HPG_OPS_CLIENT_SECRET=")).findFirst()
                .orElseThrow(() -> new IllegalStateException("HPG_OPS_CLIENT_SECRET not in deploy/env/.env"))
                .substring("HPG_OPS_CLIENT_SECRET=".length());

        System.out.printf("Setting up %d hospitals...%n", merchants);
        String ops = token("ops-console", opsSecret);
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < merchants; i++) {
            tokens.add(setUpHospital(i, ops));
        }

        System.out.printf("Running %d iterations/s (create + read = %d requests/s) for %d s...%n", rate, 2 * rate, seconds);
        samples.clear();
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        var ticker = Executors.newSingleThreadScheduledExecutor();
        AtomicLong iteration = new AtomicLong();
        AtomicInteger inFlight = new AtomicInteger(), maxInFlight = new AtomicInteger();
        long total = (long) rate * seconds;
        long start = System.nanoTime();
        ticker.scheduleAtFixedRate(() -> {
            long n = iteration.getAndIncrement();
            if (n >= total) {
                return;
            }
            String token = tokens.get((int) (n % tokens.size()));
            workers.submit(() -> {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    iterate(token);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }, 0, 1_000_000L / rate, TimeUnit.MICROSECONDS);
        while (iteration.get() < total) {
            Thread.sleep(200);
        }
        ticker.shutdownNow();
        workers.shutdown();
        workers.awaitTermination(60, TimeUnit.SECONDS);
        double elapsed = (System.nanoTime() - start) / 1e9;
        report(elapsed, maxInFlight.get());
    }

    static void iterate(String token) {
        HttpResponse<String> created = send("POST /payments", HttpRequest.newBuilder(URI.create(gateway + "/api/v1/payments"))
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(paymentBody("LOAD-" + UUID.randomUUID()))));
        if (created == null || created.statusCode() != 201) {
            return;
        }
        String id = field(created.body(), "id");
        send("GET /payments/{id}", HttpRequest.newBuilder(URI.create(gateway + "/api/v1/payments/" + id))
                .header("Authorization", "Bearer " + token).GET());
    }

    static HttpResponse<String> send(String name, HttpRequest.Builder request) {
        long t0 = System.nanoTime();
        int status;
        HttpResponse<String> response = null;
        try {
            response = http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
        } catch (Exception e) {
            status = -1; // connection error / timeout
        }
        samples.computeIfAbsent(name, k -> new ConcurrentLinkedQueue<>())
                .add(new Sample((System.nanoTime() - t0) / 1000, status));
        return response;
    }

    static void report(double elapsed, int maxInFlight) {
        System.out.printf("%nDone in %.1f s (max concurrent iterations: %d)%n%n", elapsed, maxInFlight);
        System.out.println("| Request | Count | Throughput | Errors | p50 | p90 | p95 | p99 | max |");
        System.out.println("|---|---:|---:|---:|---:|---:|---:|---:|---:|");
        for (String name : List.of("POST /payments", "GET /payments/{id}")) {
            List<Sample> list = new ArrayList<>(samples.getOrDefault(name, new ConcurrentLinkedQueue<>()));
            if (list.isEmpty()) {
                continue;
            }
            List<Long> lat = new ArrayList<>(list.stream().map(Sample::micros).toList());
            Collections.sort(lat);
            long errors = list.stream().filter(s -> s.status() < 200 || s.status() >= 300).count();
            Map<Integer, Long> byStatus = new java.util.TreeMap<>();
            list.forEach(s -> byStatus.merge(s.status(), 1L, Long::sum));
            System.out.printf("| %s | %d | %.1f/s | %.2f%% | %s | %s | %s | %s | %s |%n", name, list.size(),
                    list.size() / elapsed, 100.0 * errors / list.size(), ms(pct(lat, 50)), ms(pct(lat, 90)),
                    ms(pct(lat, 95)), ms(pct(lat, 99)), ms(lat.get(lat.size() - 1)));
            if (errors > 0) {
                System.out.println("  status codes for " + name + ": " + byStatus);
            }
        }
    }

    static long pct(List<Long> sorted, double p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    static String ms(long micros) {
        return String.format("%.1f ms", micros / 1000.0);
    }

    // --- setup -------------------------------------------------------------------------------------------

    static String setUpHospital(int i, String ops) throws Exception {
        HttpResponse<String> reg = null;
        for (int attempt = 0; attempt < 20; attempt++) { // registration is limited to 1/s per client
            reg = http.send(HttpRequest.newBuilder(URI.create(gateway + "/api/v1/merchants"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                            "{\"legalName\":\"Load Hospital " + i + " Pvt Ltd\",\"displayName\":\"Load Hospital " + i
                                    + "\",\"contactEmail\":\"load-" + System.nanoTime() + "@load.example\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (reg.statusCode() != 429) {
                break;
            }
            Thread.sleep(1100);
        }
        if (reg.statusCode() != 201) {
            throw new IllegalStateException("registration failed: " + reg.statusCode() + " " + reg.body());
        }
        String body = reg.body();
        Matcher m = Pattern.compile("\"merchant\":\\{\"id\":\"([^\"]+)\"").matcher(body);
        if (!m.find()) {
            throw new IllegalStateException("no merchant id in " + body);
        }
        String merchantId = m.group(1);
        http.send(HttpRequest.newBuilder(URI.create(gateway + "/api/v1/admin/merchants/" + merchantId + "/activate"))
                .header("Authorization", "Bearer " + ops).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"load test\"}")).build(),
                HttpResponse.BodyHandlers.discarding());
        String token = token(field(body, "keyId"), field(body, "keySecret"));
        // Activation reaches payment-service asynchronously (merchant.snapshots.v1): wait until it accepts payments.
        for (int attempt = 0; attempt < 60; attempt++) {
            int status = http.send(HttpRequest.newBuilder(URI.create(gateway + "/api/v1/payments"))
                    .header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(paymentBody("WARMUP-" + UUID.randomUUID()))).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 201) {
                return token;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("hospital " + i + " never became payable");
    }

    static String token(String clientId, String secret) throws Exception {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes());
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(gateway + "/oauth2/token"))
                .header("Authorization", "Basic " + basic).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("token request failed: " + r.statusCode());
        }
        return field(r.body(), "access_token");
    }

    static String paymentBody(String reference) {
        return "{\"merchantReference\":\"" + reference + "\",\"amount\":{\"amount\":\"10.00\",\"currency\":\"INR\"},"
                + "\"description\":\"Load test\"}";
    }

    /** First string value of a JSON field; enough for the flat responses used here (no JSON library needed). */
    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("no " + name + " in " + json);
        }
        return m.group(1);
    }
}
