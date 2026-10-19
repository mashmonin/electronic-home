package com.example.electronichome;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Shared helpers of the reading period integration tests. */
final class PeriodTestSupport {

    static final String ACCOUNT = "000000000001";

    private PeriodTestSupport() {
    }

    /** A clock whose date the test sets. */
    static final class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-10-18T09:00:00Z");

        void set(String date) {
            instant = LocalDate.parse(date).atTime(9, 0).toInstant(ZoneOffset.UTC);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static HttpResponse<String> get(int port, String account) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/accounts/" + account))
                .header("Authorization", AuthSupport.adminBearer(port)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(int port, String account, String... readings) throws Exception {
        String body = "{\"readings\":[" + String.join(",", readings) + "]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/accounts/" + account + "/readings"))
                .header("Content-Type", "application/json")
                .header("Authorization", AuthSupport.adminBearer(port))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    static String reading(String meter, String value) {
        return "{\"meter_number\":\"" + meter + "\",\"value\":" + value + "}";
    }
}
