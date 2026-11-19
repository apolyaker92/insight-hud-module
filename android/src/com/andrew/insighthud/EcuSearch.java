package com.andrew.insighthud;

import java.io.IOException;
import java.util.Locale;

final class EcuSearch {
    interface Link {
        String ask(String command, long timeoutMs) throws IOException, InterruptedException;
    }

    static final class Result {
        final byte[] supported;
        final String protocol;

        Result(byte[] supported, String protocol) {
            this.supported = supported;
            this.protocol = protocol;
        }
    }

    static final String DEFAULT_PROTOCOL = "ATSP3";
    private static final String[] ALL = {DEFAULT_PROTOCOL, "ATSP0", "ATSP4", "ATSP5"};

    private EcuSearch() { }

    static String[] order(String preferred) {
        String first = preferred == null || preferred.isEmpty() ? DEFAULT_PROTOCOL : preferred;
        String[] result = new String[ALL.length];
        result[0] = first;
        int next = 1;
        for (String candidate : ALL) {
            if (!candidate.equals(first) && next < result.length) result[next++] = candidate;
        }
        return result;
    }

    static boolean busFailed(String reply) {
        String text = reply.toUpperCase(Locale.US);
        return text.contains("ERROR") || text.contains("UNABLE TO CONNECT") || text.contains("BUS BUSY")
                || text.contains("NO DATA");
    }

    static Result find(Link link, String preferred) throws IOException, InterruptedException {
        String[] protocols = order(preferred);
        for (int index = 0; index < protocols.length; index++) {
            String protocol = protocols[index];
            if (!link.ask(protocol, 2000).contains("OK")) continue;
            int tries = index == 0 ? 2 : 1;
            for (int attempt = 0; attempt < tries; attempt++) {
                String reply = link.ask("0100", 15000);
                byte[] supported = ObdProtocol.payload(reply, 0, 4);
                if (supported != null) return new Result(supported, protocol);
                if (!busFailed(reply)) break;
            }
        }
        throw new IOException("ECU did not respond");
    }
}
