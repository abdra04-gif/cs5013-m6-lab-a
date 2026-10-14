import java.nio.file.Path;
import java.util.List;

/**
 * Task (6)/(7): retrieval-only quality signal. For each of the ten
 * in-corpus questions in {@link Main#QUESTIONS}, a hit means some top-k
 * chunk from the expected file covers one of the "answer" lines below.
 * Line-based, so the method chunker and the 50-line chunker are judged
 * by the same yardstick.
 *
 *   make eval              # method-level chunker
 *   CHUNKER=lines make eval
 */
public class RetrievalEval {

    record Gold(String file, int... lines) {}

    /** Parallel to the first ten entries of Main.QUESTIONS. */
    static final Gold[] GOLD = {
        new Gold("util/FinePolicy.java", 32, 34),            // computeFine body
        new Gold("util/Catalog.java", 17, 20),               // Map<String,Book> byIsbn / put
        new Gold("core/Loan.java", 42, 50),                  // markReturned / returnedOn != null
        new Gold("util/FinePolicy.java", 25, 35),            // CAP = 500.00
        new Gold("core/LoanRegistry.java", 17),              // record(): history.add
        new Gold("core/Member.java", 45),                    // activeLoans.size() >= 5
        new Gold("core/Loan.java", 12),                      // DEFAULT_TERM_DAYS = 21
        new Gold("core/LoanRegistry.java", 37),              // overdueOn(): l.isOverdue
        new Gold("core/Book.java", 54),                      // equals on isbn
        new Gold("util/Catalog.java", 35),                   // searchByTitle contains()
    };

    public static void main(String[] args) throws Exception {
        int k = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        Embedder emb = new MiniLmEmbedder();
        RepoIndexer idx = new RepoIndexer(emb);
        idx.indexDirectory(Path.of("sample-repo"));
        RagAssistant ra = new RagAssistant(idx, emb, null, Path.of("prompts/rag.txt"));

        int hits = 0;
        long ctxChars = 0;
        System.out.printf("chunker=%s chunks=%d k=%d%n",
                System.getenv().getOrDefault("CHUNKER", "method"), idx.size(), k);
        for (int q = 0; q < GOLD.length; q++) {
            List<Chunk> top = ra.retrieve(Main.QUESTIONS[q], k);
            int rank = rankOf(top, GOLD[q]);
            if (rank > 0) hits++;
            for (Chunk c : top) ctxChars += c.content().length();
            System.out.printf("Q%-2d %-5s rank=%-2s %s%n", q + 1, rank > 0 ? "HIT" : "MISS",
                    rank > 0 ? rank : "-", Main.QUESTIONS[q]);
        }
        System.out.printf(java.util.Locale.ROOT, "hit-rate@%d = %d/%d = %.2f   avg context = %d chars (~%d tokens) per query%n",
                k, hits, GOLD.length, hits / (double) GOLD.length,
                ctxChars / GOLD.length, ctxChars / GOLD.length / 4);
    }

    /** 1-based rank of the first chunk covering a gold line, or 0. */
    static int rankOf(List<Chunk> top, Gold g) {
        for (int r = 0; r < top.size(); r++) {
            Chunk c = top.get(r);
            if (!c.source().replace('\\', '/').endsWith(g.file())) continue;
            for (int line : g.lines()) {
                if (line >= c.startLine() && line <= c.endLine()) return r + 1;
            }
        }
        return 0;
    }
}
