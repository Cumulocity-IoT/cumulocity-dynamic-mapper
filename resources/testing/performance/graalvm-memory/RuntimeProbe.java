import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;

/**
 * Probes how GraalJS runs on the JDK it is started with, and what closing an Engine reclaims.
 * Run through verify-graalvm-runtime.sh (it supplies the classpath). Args: [distinctSources].
 *
 * Part 1  Is there a Graal compiler? (JDK modules, Truffle's own warning, hot-loop timing)
 * Part 2  Metaspace / code cache / heap while loading N distinct sources into one Engine,
 *         and after closing the contexts and the Engine.
 */
public class RuntimeProbe {

    public static void main(String[] args) throws Exception {
        int sources = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        partOneRuntime();
        partTwoMemory(sources);
    }

    // ── Part 1 ────────────────────────────────────────────────────────────

    static void partOneRuntime() {
        section("1. Runtime");
        System.out.println("java.vm.name        : " + System.getProperty("java.vm.name"));
        System.out.println("java.vendor         : " + System.getProperty("java.vendor"));
        System.out.println("java.vendor.version : " + System.getProperty("java.vendor.version"));
        System.out.println("java.version        : " + System.getProperty("java.version"));
        for (String module : new String[] { "jdk.graal.compiler", "jdk.internal.vm.compiler", "jdk.internal.vm.ci" }) {
            System.out.printf("module %-26s: %s%n", module,
                    ModuleLayer.boot().findModule(module).isPresent() ? "present" : "absent");
        }

        // Truffle logs a warning at Engine creation when it has no optimizing runtime. Capture it.
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        boolean noCompiler;
        try (Engine engine = Engine.newBuilder().logHandler(log).build();
                Context context = Context.newBuilder("js").engine(engine).build()) {
            context.eval("js", "1+1");
        }
        String text = log.toString();
        noCompiler = text.toLowerCase().contains("runtime compilation")
                || text.toLowerCase().contains("interpreter");
        System.out.println("Truffle warning     : " + (text.isBlank() ? "(none)" : text.strip().replace('\n', ' ')));

        // Compiler-only options are normally registered only by the optimizing runtime.
        boolean traceOptionKnown;
        try (Engine engine = Engine.newBuilder().option("engine.TraceCompilation", "false")
                .logHandler(new ByteArrayOutputStream()).build()) {
            traceOptionKnown = true;
        } catch (IllegalArgumentException e) {
            traceOptionKnown = false;
        }
        System.out.println("engine.TraceCompilation option known: " + traceOptionKnown);

        // Behavioural check: a hot loop. Interpreter-only does not get faster across repeats.
        System.out.println("hot loop (3e7 iterations), ms per run:");
        long[] runs = new long[4];
        try (Engine engine = Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build();
                Context context = Context.newBuilder("js").engine(engine).build()) {
            Source loop = Source.create("js", "(function(){var x=0;for(var i=0;i<3e7;i++){x+=i%7;}return x;})()");
            for (int i = 0; i < runs.length; i++) {
                long start = System.nanoTime();
                context.eval(loop);
                runs[i] = (System.nanoTime() - start) / 1_000_000;
            }
        }
        System.out.println("  " + java.util.Arrays.toString(runs));
        boolean speedsUp = runs[runs.length - 1] * 3 < runs[0];

        System.out.println();
        boolean interpreterOnly = noCompiler || !traceOptionKnown || !speedsUp;
        System.out.println("VERDICT: " + (interpreterOnly
                ? "INTERPRETER-ONLY (no Graal compiler in effect) — the 'Graal JIT fills Metaspace' comments do not apply"
                : "Graal compiler appears ACTIVE — JIT output exists and the Metaspace theory is plausible"));
        System.out.println("  signals: truffleWarning=" + noCompiler + " traceOptionUnknown=" + !traceOptionKnown
                + " loopDidNotSpeedUp=" + !speedsUp);
    }

    // ── Part 2 ────────────────────────────────────────────────────────────

    static void partTwoMemory(int sources) {
        section("2. Memory while loading " + sources + " distinct sources into ONE Engine");
        System.out.printf("%-34s %12s %12s %12s%n", "step", "metaspace MB", "codecache MB", "heap MB");
        snapshot("baseline (Truffle classes already loaded by part 1)");

        List<Context> contexts = new ArrayList<>();
        Engine engine = Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build();
        Snapshot afterEngine = snapshot("engine created");

        int step = Math.max(1, sources / 3);
        for (int i = 1; i <= sources; i++) {
            Context context = Context.newBuilder("js").engine(engine).build();
            // a distinct source per iteration, like distinct mappings
            context.eval(Source.newBuilder("js",
                    "function onMessage(m){var r={id:" + i + ",v:m.x*" + i + "};return [r];} onMessage({x:1});",
                    "mapping_" + i + ".js").buildLiteral());
            contexts.add(context);
            if (i % step == 0 || i == sources) {
                snapshot("after " + i + " sources (" + i + " ctx open)");
            }
        }
        Snapshot loaded = snapshot("loaded, contexts open");

        for (Context context : contexts) {
            context.close();
        }
        Snapshot contextsClosed = snapshot("contexts closed, engine open");

        engine.close();
        // Drop our references too: a closed Engine we still hold cannot be collected, which would
        // make "closing the Engine frees nothing" an artefact of the probe.
        engine = null;
        contexts = null;
        Snapshot engineClosed = snapshot("engine closed, references dropped");

        System.out.println();
        System.out.printf("Metaspace  : loaded %+.1f MB vs engine-created;  closing the Engine freed %.1f MB%n",
                loaded.metaspace - afterEngine.metaspace, contextsClosed.metaspace - engineClosed.metaspace);
        System.out.printf("Heap       : loaded %+.1f MB vs engine-created;  closing contexts freed %.1f MB, Engine %.1f MB%n",
                loaded.heap - afterEngine.heap, loaded.heap - contextsClosed.heap,
                contextsClosed.heap - engineClosed.heap);
        double metaspaceGrowth = loaded.metaspace - afterEngine.metaspace;
        double metaspaceFreed = contextsClosed.metaspace - engineClosed.metaspace;
        System.out.println();
        System.out.println("READING:");
        if (metaspaceGrowth < 5 && metaspaceFreed < 5) {
            System.out.println("  Metaspace stays flat across sources and Engine.close() -> the Metaspace theory behind "
                    + "engine rotation is NOT supported; rotation can only reclaim heap.");
        } else if (metaspaceFreed >= metaspaceGrowth * 0.5) {
            System.out.println("  Metaspace grows with sources and is released by Engine.close() -> rotation does "
                    + "reclaim Metaspace; keep it and tune the threshold.");
        } else {
            System.out.println("  Metaspace grows with sources but Engine.close() does not release it -> rotation "
                    + "does not help Metaspace (classes stay loaded); look for a leak or raise the limit.");
        }
        System.out.println("  (Compare with ENGINE_ROTATION_THRESHOLD=1000 in GraalVMContextService.)");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    record Snapshot(double metaspace, double codeCache, double heap) {}

    static Snapshot snapshot(String label) {
        System.gc();
        try {
            Thread.sleep(150);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        System.gc();
        double metaspace = 0;
        double codeCache = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            double used = pool.getUsage().getUsed() / 1048576.0;
            if (pool.getName().equals("Metaspace") || pool.getName().equals("Compressed Class Space")) {
                metaspace += used;
            } else if (pool.getName().startsWith("CodeHeap")) {
                codeCache += used;
            }
        }
        Runtime rt = Runtime.getRuntime();
        double heap = (rt.totalMemory() - rt.freeMemory()) / 1048576.0;
        System.out.printf("%-34s %12.1f %12.1f %12.1f%n", label, metaspace, codeCache, heap);
        return new Snapshot(metaspace, codeCache, heap);
    }

    static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }
}
