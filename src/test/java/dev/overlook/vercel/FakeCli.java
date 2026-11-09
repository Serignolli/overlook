package dev.overlook.vercel;

import dev.overlook.Fixtures;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Processo que se passa pela Vercel CLI nos testes. Responde com as fixtures reais
 * conforme os argumentos. Flags próprias vêm antes e começam com {@code --fake-}.
 */
public final class FakeCli {

    public static void main(String[] argv) throws Exception {
        String version = "63.1.0";
        List<String> args = new ArrayList<>();
        for (String a : argv) {
            if (a.startsWith("--fake-version=")) {
                version = a.substring("--fake-version=".length());
            } else {
                args.add(a);
            }
        }
        String first = args.isEmpty() ? "" : args.get(0);
        switch (first) {
            case "--version" -> System.out.println("Vercel CLI " + version + "\n" + version);
            case "echo-args" -> {
                args.forEach(System.out::println);
                System.out.println("ENV_TOKEN=" + (System.getenv("VERCEL_TOKEN") != null));
                System.out.println("ENV_OTHER=" + (System.getenv("OVERLOOK_TEST_OTHER_SECRET") != null));
                System.out.println("ENV_NO_COLOR=" + System.getenv("NO_COLOR"));
            }
            case "sleep" -> Thread.sleep(20_000);
            case "teams" -> System.out.print(Fixtures.read("teams.json"));
            case "project" -> {
                String json = Fixtures.read("projects.json");
                if (args.contains("--next")) {
                    json = json.replace("1789990000000", "null").replace("prj_aaa", "prj_ccc").replace("prj_bbb", "prj_ddd");
                }
                System.out.print(json);
            }
            case "metrics" -> {
                if (args.contains("--token") || args.stream().anyMatch(a -> a.startsWith("--token"))) {
                    System.exit(99);
                }
                if (args.size() > 1 && args.get(1).equals("schema")) {
                    System.out.print(Fixtures.read("schema.json"));
                } else if (args.contains("acme-limited")) {
                    System.out.print(Fixtures.read("errors/rate-limited.json"));
                    System.exit(1);
                } else {
                    System.out.print(Fixtures.read("pageviews-by-project.json"));
                }
            }
            case "fail-login" -> {
                System.err.print(Fixtures.read("errors/no-credentials.stderr.txt"));
                System.exit(1);
            }
            default -> {
                System.err.println("unknown: " + Arrays.toString(argv));
                System.exit(2);
            }
        }
    }

    /** Prefixo de comando para rodar este processo com a JVM atual. */
    public static List<String> command(String... fakeFlags) {
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"),
                "-cp", System.getProperty("java.class.path"),
                FakeCli.class.getName()));
        cmd.addAll(List.of(fakeFlags));
        return cmd;
    }
}
