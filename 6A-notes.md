# Session 6A – RAG over a local Java repo: notes

**Setup used**

| Component | Choice |
|---|---|
| Chunker | Hand-rolled brace-balanced splitter (`RepoIndexer.chunkFile`). It skips string, char and text-block literals and comments. Each method/constructor becomes one chunk, prefixed with `// File: <path>` and `// Class: <enclosing type signature>`, and keeps its JavaDoc and annotations. Each type also gets one *header* chunk with its JavaDoc, its signature, its fields and its body-less (interface) methods, so constants such as `CAP` and `DEFAULT_TERM_DAYS` can be retrieved. |
| Embedder | `MiniLmEmbedder`: all-MiniLM-L6-v2 (384-dim, in-process ONNX, langchain4j 0.36.0) |
| Retriever | Full cosine over every chunk; top-k = 8 |
| LLM | Ollama `llama3.2:3b`, temperature 0 (8 GB RAM machine; override with `OLLAMA_MODEL`) |
| Version guard | `RepoIndexer` records `Embedder.modelId()` at index time, and `RagAssistant.retrieve` throws `IllegalStateException` if the query embedder differs |

Raw outputs are in `runs/`: `run-method.txt`, `run-lines.txt` and `run-noguard.txt`.
`make eval` (or `CHUNKER=lines make eval`) prints the retrieval hit-rate.

## 1. Indexer stats (task 3)

| Configuration | Chunks | Index time |
|---|---|---|
| Starter stub chunker (one chunk per file) + `StubEmbedder` | **8** | 0.011 s |
| Method chunker + `MiniLmEmbedder` | **51** (42 methods/ctors + 9 type headers) | 0.401 s |
| 50-line chunker + `MiniLmEmbedder` (task 7) | 12 | 0.185 s |

Going from 8 to 51 chunks gives about 6.4× more chunks. The 42 method chunks match a hand count of the methods in the 8 files, so the chunker is not merging methods.
End-to-end, `make run` takes about 43 s for 11 questions, which is about 4 s per answer on llama3.2:3b.

## 2. Ten questions (tasks 5–6), method chunker, k = 8

"Gold" is the chunk that contains the answer. Rank is that chunk's position in the top-8.

| # | Question | Gold chunk | Rank | LLM answer (abridged) | Judgement |
|---|---|---|---|---|---|
| 1 | Where is the fine for an overdue loan computed? | FinePolicy.java:27-36 `StandardFinePolicy.computeFine` | 6 | "computed by the `finePolicy` using the `computeFine` method … FinePolicy.java:27-36" | ✅ correct |
| 2 | What class holds the catalogue of books, and how is it indexed? | Catalog.java:11-17 (`Map<String,Book> byIsbn`) | 1 | "`Catalog` … indexed by ISBN … a `Map` where the key is the ISBN" | ✅ correct |
| 3 | How does the system decide a loan has been returned? | Loan.java:41-43 / 45-54 (`returnedOn`) | 1 | "returned when `returnedOn` is not null … `isOverdue` returns false" | ✅ correct, but does not mention `markReturned` |
| 4 | What is the maximum fine the policy allows? | FinePolicy.java:22-25 (`CAP`) | 2 | "500 rupees … `CAP = new BigDecimal("500.00")`" | ✅ correct |
| 5 | Which methods write to the loan registry? | LoanRegistry.java:16-18 `record` | 2 | "`record(Loan)` … and `loansFor(String)`" | ⚠️ partly wrong: `loansFor` only reads, and it misses `LibraryService.borrow`, which calls `registry.record` |
| 6 | How many books can a member have on loan at the same time? | Member.java:41-49 `addLoan` | 2 | "at most 5 open loans … `addLoan` throws `IllegalStateException`" | ✅ correct |
| 7 | How long is the default loan term? | Loan.java:6-17 (`DEFAULT_TERM_DAYS = 21`) | 2 | "21 days … Loan.java:6-17" | ✅ correct |
| 8 | How does the report service find overdue loans? | ReportService.java:28-30 → LoanRegistry.overdueOn | 7 (overdueOn), 1 (ReportService.overdue) | "calls `overdueOn` on the registry … ReportService.java:28-30" | ✅ correct |
| 9 | When are two Book objects considered equal? | Book.java:47-55 `equals` | 1 | "if they share an ISBN … `return isbn.equals(other.isbn);`" | ✅ correct |
| 10 | How does searching for books by title work? | Catalog.java:27-40 `searchByTitle` | 1 | "lower-cases … checks `contains` … `searchByTitle`, Catalog.java:27-40" | ✅ correct (wording a bit muddled) |

**Retrieval hit-rate@8 = 10/10 = 1.00.** The gold chunk was in the top-8 for every question, and it was at rank ≤ 2 for 8 of the 10.
**Generation:** 9/10 answers are correct and 1 is partly wrong (Q5).
In Q5 the retriever did surface `record`, but `LibraryService.borrow` was not in the top-8. The 3B model also misread `loansFor` as a writer. That is a generation error, not a retrieval error.

Hit-rate at smaller k, method chunker: @4 = 0.80, @2 = 0.80, @1 = 0.40.

**Prompt iteration.** The first prompt said "If the passages do not contain the answer, reply exactly: …" and put the context after the question. With that prompt, llama3.2:3b refused 8 of the 10 in-corpus questions, even though retrieval was correct.
The current `prompts/rag.txt` makes three changes:
- the passages come before the question;
- the refusal applies only if *none* of the passages contains the answer;
- the model is asked to cite file:lines.

With these changes the model answers all 10 questions and still refuses the out-of-corpus one.

## 2a. Per-question detail: answer, top-8 chunks, judgement (task 5)

From `runs/run-method.txt` (method chunker, MiniLM, llama3.2:3b, k = 8, guarded prompt). The **bold** rows are the chunks that contain the answer.

### Q1. Where is the fine for an overdue loan computed?

Top-8 chunks:

1. util/FinePolicy.java:9-15
2. core/Loan.java:45-54
3. api/ReportService.java:28-30
4. core/LoanRegistry.java:34-42
5. api/LibraryService.java:43-45
6. **util/FinePolicy.java:27-36**
7. core/Loan.java:33-35
8. core/Loan.java:37-39

Answer:

> The fine for an overdue loan is computed by the `finePolicy` using the `computeFine` method, which takes a `Loan` and a `LocalDate` as parameters. The relevant code is in `sample-repo/com/example/util/FinePolicy.java:27-36`, where the `computeFine` method is implemented.
>
> [6] sample-repo/com/example/util/FinePolicy.java:27-36

Judgement: ✅ Correct. It names `computeFine` in FinePolicy.java:27-36.

### Q2. What class holds the catalogue of books, and how is it indexed?

Top-8 chunks:

1. **util/Catalog.java:11-17**
2. core/Book.java:6-17
3. api/LibraryService.java:47-49
4. util/Catalog.java:23-25
5. util/Catalog.java:19-21
6. core/Book.java:27-29
7. util/Catalog.java:27-40
8. api/LibraryService.java:30-41

Answer:

> The catalogue of books is held by the `Catalog` class, which is indexed by ISBN. The catalogue is implemented as a `Map` where the key is the ISBN and the value is the corresponding `Book` object. The `Catalog` class provides a method `searchByTitle` for case-insensitive substring search over the title, and a method `findByIsbn` for finding a book by its ISBN.

Judgement: ✅ Correct. The class is `Catalog`, keyed by ISBN in a `Map<String, Book>`.

### Q3. How does the system decide a loan has been returned?

Top-8 chunks:

1. **core/Loan.java:45-54**
2. core/Loan.java:37-39
3. core/Loan.java:33-35
4. core/Loan.java:6-17
5. core/LoanRegistry.java:8-14
6. core/LoanRegistry.java:24-32
7. core/LoanRegistry.java:16-18
8. core/Loan.java:29-31

Answer:

> The system decides a loan has been returned when the `returnedOn` field of the `Loan` object is not null. This is implemented in the `isOverdue` method, where it returns `false` when `returnedOn` is not null, indicating that the loan has been returned. (sample-repo/com/example/core/Loan.java:45-54)

Judgement: ✅ Correct (`returnedOn != null`). It does not mention `markReturned(LocalDate)`, which sets the field.

### Q4. What is the maximum fine the policy allows?

Top-8 chunks:

1. **util/FinePolicy.java:9-15**
2. util/FinePolicy.java:27-36
3. **util/FinePolicy.java:22-25**
4. core/Member.java:41-49
5. api/LibraryService.java:43-45
6. util/FinePolicy.java:17-19
7. api/LibraryService.java:24-28
8. core/Loan.java:6-17

Answer:

> The maximum fine the policy allows is 500 rupees. This is defined in the `StandardFinePolicy` class as `private static final BigDecimal CAP = new BigDecimal("500.00");`.

Judgement: ✅ Correct: 500 rupees, the `CAP` constant.

### Q5. Which methods write to the loan registry?

Top-8 chunks:

1. core/LoanRegistry.java:8-14
2. **core/LoanRegistry.java:16-18**
3. api/ReportService.java:11-18
4. core/LoanRegistry.java:34-42
5. core/Loan.java:6-17
6. core/LoanRegistry.java:24-32
7. core/LoanRegistry.java:20-22
8. core/Loan.java:45-54

Answer:

> The methods that write to the loan registry are `record(Loan loan)` in `LoanRegistry.java:16-18` and `loansFor(String memberId)` in `LoanRegistry.java:24-32`.

Judgement: ⚠️ Partly wrong. `record` is right, but `loansFor` only reads. It misses `LibraryService.borrow` → `registry.record(loan)`, which was not in the top-8.

### Q6. How many books can a member have on loan at the same time?

Top-8 chunks:

1. core/Loan.java:6-17
2. **core/Member.java:41-49**
3. core/Book.java:6-17
4. core/Loan.java:19-23
5. api/LibraryService.java:30-41
6. api/ReportService.java:32-43
7. core/Loan.java:25-27
8. core/LoanRegistry.java:24-32

Answer:

> A member can have at most 5 open loans. This is enforced by the `addLoan` method in the `Member` class, which throws an `IllegalStateException` if the member already has 5 open loans.
>
> File: sample-repo/com/example/core/Member.java, lines 41-49.

Judgement: ✅ Correct: 5 open loans, enforced in `Member.addLoan`.

### Q7. How long is the default loan term?

Top-8 chunks:

1. core/Loan.java:45-54
2. **core/Loan.java:6-17**
3. util/FinePolicy.java:27-36
4. core/LoanRegistry.java:8-14
5. core/LoanRegistry.java:16-18
6. core/Loan.java:33-35
7. core/Loan.java:37-39
8. core/Member.java:41-49

Answer:

> The default loan term is 21 days. According to the code in file: sample-repo/com/example/core/Loan.java:6-17, "A loan is either open (returnedOn == null) or closed. Loans default to a 21-day term."

Judgement: ✅ Correct: 21 days (`DEFAULT_TERM_DAYS`).

### Q8. How does the report service find overdue loans?

Top-8 chunks:

1. **api/ReportService.java:28-30**
2. api/ReportService.java:11-18
3. api/ReportService.java:32-43
4. api/ReportService.java:20-22
5. core/LoanRegistry.java:8-14
6. core/Loan.java:45-54
7. **core/LoanRegistry.java:34-42**
8. core/LoanRegistry.java:16-18

Answer:

> The report service finds overdue loans by calling the `overdueOn` method on the `registry` object, which returns a list of loans that are overdue on the specified date. This is implemented in `ReportService.java:28-30`.

Judgement: ✅ Correct: `ReportService.overdue` → `LoanRegistry.overdueOn`.

### Q9. When are two Book objects considered equal?

Top-8 chunks:

1. **core/Book.java:47-55**
2. core/Book.java:6-17
3. core/Book.java:19-25
4. util/Catalog.java:42-50
5. util/Catalog.java:19-21
6. api/LibraryService.java:30-41
7. core/Member.java:51-53
8. core/Book.java:27-29

Answer:

> Two Book objects are considered equal if they share an ISBN. This is implemented in the equals() method of the Book class, which checks if the ISBN of the current object is equal to the ISBN of the object passed as a parameter: `return isbn.equals(other.isbn);` (file: sample-repo/com/example/core/Book.java:47-55).

Judgement: ✅ Correct: equal when the ISBNs are equal.

### Q10. How does searching for books by title work?

Top-8 chunks:

1. **util/Catalog.java:27-40**
2. api/LibraryService.java:47-49
3. util/Catalog.java:11-17
4. core/Book.java:31-33
5. core/Book.java:62-65
6. util/Catalog.java:23-25
7. core/Book.java:6-17
8. api/LibraryService.java:30-41

Answer:

> Searching for books by title works by converting the input title to lowercase and then checking if it contains the lowercase version of the input string. This is done in the `searchByTitle` method of the `Catalog` class, as shown in file: `sample-repo/com/example/util/Catalog.java:27-40`.

Judgement: ✅ Essentially correct: a case-insensitive `contains` over titles. The wording is slightly muddled.

### Q11 (not in corpus). Which Kafka topic does the library publish loan events to?

Top-8 chunks:

1. core/LoanRegistry.java:8-14
2. api/LibraryService.java:30-41
3. api/LibraryService.java:14-22
4. core/Loan.java:25-27
5. core/LoanRegistry.java:16-18
6. core/Loan.java:6-17
7. core/Loan.java:33-35
8. core/Book.java:6-17

Answer:

> I do not find that in the passages.

Judgement: ✅ Correct refusal: there is no Kafka in the repo (task 8).

## 3. "Bad chunker" experiment (task 7): split every 50 lines

`CHUNKER=lines make run` / `CHUNKER=lines make eval`

| Metric | Method chunker | 50-line chunker |
|---|---|---|
| Chunks | 51 | 12 |
| hit-rate@8 | 1.00 | 1.00 (no change) |
| hit-rate@4 | 0.80 | 1.00 |
| hit-rate@2 | 0.80 | 0.90 |
| hit-rate@1 | 0.40 | 0.60 |
| Avg. context per query @8 | 2 454 chars (≈ 610 tokens) | 8 604 chars (≈ 2 150 tokens) |

**Fraction change at k = 8: 1.00 → 1.00 (0 pp).** The coarse chunker even "wins" at small k.
This does not show that the chunker is good. It is an artefact of the tiny corpus:
- Every file is ≤ 66 lines, so a 50-line chunk is basically a whole file. 8 files become 12 chunks, and the top-8 holds about ⅔ of the entire repository.
- "Was the gold line in the top-8?" is trivially true when the top-8 is most of the repo.

The damage shows up elsewhere:
- **About 3.5× more tokens per query** (≈ 2 150 vs ≈ 610 tokens) for the same hit-rate. A good top-4 (≈ 320 tokens) already gets 0.80.
- **Line citations are lost.** With whole-file chunks the model cannot tell where the answer is, so it invents line numbers. Examples:
  - "FinePolicy.java lines 23-28" for `computeFine` (actually 27-36);
  - "Loan.java line 34: `DEFAULT_TERM_DAYS = 21`" (actually line 12);
  - "Member.java lines 34-36" (actually 41-49);
  - "LoanRegistry.java:31-34" (actually 34-42).

  With method chunks, every cited range was correct.
- **Wrong facts.** Q5 now lists `Loan.markReturned` as a write to the loan registry. It writes to `Loan`, not to the registry; the model saw the whole Loan file and over-generalised.
- **Embedding truncation.** all-MiniLM-L6-v2 truncates input at 256 word-pieces, so a 50-line chunk is embedded mostly from its imports and first members. On a real corpus with files of hundreds of lines, this is what drives the hit-rate down. Here the files are too short to show it.

Conclusion: on this corpus, chunking shows up in cost and in answer precision (citations, wrong facts) rather than in hit-rate@8. Raising k to compensate would only make the token cost worse.

## 4. "Question not in corpus" experiment (task 8)

Question added to `QUESTIONS`: *"Which Kafka topic does the library publish loan events to?"* The repo has no Kafka and no event publishing at all.

| Prompt | Answer |
|---|---|
| With the anti-hallucination lines (2–3 of `prompts/rag.txt`) | **"I do not find that in the passages."** ✅ |
| Lines 2–3 deleted | *"The library publishes loan events to a Kafka topic called "loan-events". This is indicated by the `registry.record(loan)` line in the `LoanRegistry` class (file: [3] sample-repo/com/example/api/LibraryService.java:14-22). The `record` method is responsible for adding a new loan to the loan history, which is then published to the "loan-events" topic."* ❌ invented, with a fake citation |

Without the guard, the model invents a topic name and backs it with a real but irrelevant citation. That is worse than a plain wrong answer, because the citation makes it look grounded.
The instruction lines were put back afterwards: `prompts/rag.txt` is in its guarded form. With the 50-line chunker and the guard, the model also refused correctly.

## Appendix: submitted source code

Repository: https://github.com/abdra04-gif/cs5013-m6-lab-a (branch `session-6a`). The tests are in `test/RagPipelineTest.java` (11/11 pass with `make test`).

### `src/RepoIndexer.java`

Task 1: method-level chunker (plus the 50-line chunker for task 7).

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Walks a directory of .java files, splits each file into method-level
 * chunks, embeds each chunk, and stores the result in an in-memory index.
 *
 * Session 6A: complete {@link #chunkFile(Path)} and {@link #embed(String)}.
 *
 * Chunking strategy is chosen by the {@code CHUNKER} environment variable:
 * unset / {@code method} (default) splits at method boundaries;
 * {@code lines} splits every {@link #FIXED_WINDOW} lines (task 7's
 * deliberately bad chunker).
 */
public class RepoIndexer {

    /** Window size for the fixed-size "bad" chunker of task (7). */
    public static final int FIXED_WINDOW = 50;

    private static final Pattern TYPE_DECL =
            Pattern.compile("\\b(class|interface|enum|record)\\s+\\w+");

    private final Embedder embedder;
    private final String embedderId;
    private final boolean fixedWindow;
    private final List<Chunk> index = new ArrayList<>();

    public RepoIndexer(Embedder embedder) {
        this(embedder, "lines".equalsIgnoreCase(System.getenv("CHUNKER")));
    }

    public RepoIndexer(Embedder embedder, boolean fixedWindow) {
        this.embedder = embedder;
        this.embedderId = embedder.modelId();
        this.fixedWindow = fixedWindow;
    }

    /** Walks dir, indexing every *.java file below it. */
    public void indexDirectory(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                 .sorted()
                 .forEach(this::indexFile);
        }
    }

    private void indexFile(Path file) {
        try {
            List<Chunk> chunks = fixedWindow ? chunkFixed(file, FIXED_WINDOW) : chunkFile(file);
            for (Chunk c : chunks) {
                float[] v = embed(c.content());
                index.add(c.withEmbedding(v));
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to index " + file, e);
        }
    }

    /**
     * Splits the file into method-level chunks with a brace-balanced
     * scanner that skips string/char literals and comments.
     *
     * Every chunk is prefixed with the file path and the enclosing type
     * signature, and keeps the method's leading JavaDoc and annotations.
     * Each type additionally gets one "header" chunk holding its JavaDoc,
     * signature, fields and body-less (abstract/interface) methods, so
     * constants such as a fine cap are retrievable too.
     *
     * Falls back to a single whole-file chunk if nothing is detected.
     */
    protected List<Chunk> chunkFile(Path file) throws IOException {
        String src = Files.readString(file);
        String path = file.toString();
        List<Chunk> out = new ArrayList<>();

        // One frame per open '{'. Root (depth 0) is represented by a null type.
        Deque<Frame> stack = new ArrayDeque<>();
        int segStart = 0;   // start of the current member/statement at the innermost type level

        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                i = skipTo(src, i, "\n");
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i = skipTo(src, i + 2, "*/");
                continue;
            }
            if (c == '"' && src.startsWith("\"\"\"", i)) {
                i = skipTo(src, i + 3, "\"\"\"");
                continue;
            }
            if (c == '"' || c == '\'') {
                i = skipLiteral(src, i, c);
                continue;
            }

            Frame top = stack.peek();
            boolean atTypeLevel = top == null || top.kind == Kind.TYPE;

            if (c == '{') {
                Frame f;
                if (atTypeLevel) {
                    int start = firstNonBlank(src, segStart, i);
                    String header = stripComments(src.substring(start, i));
                    if (TYPE_DECL.matcher(header).find()) {
                        f = new Frame(Kind.TYPE, start, collapse(header));
                        f.open = i;
                    } else if (top != null && isMethodHeader(header)) {
                        f = new Frame(Kind.METHOD, start, null);
                    } else {
                        f = new Frame(Kind.OTHER, start, null);
                    }
                } else {
                    f = new Frame(Kind.OTHER, i, null);
                }
                stack.push(f);
                segStart = i + 1;
            } else if (c == '}') {
                Frame f = stack.pop();
                Frame parent = stack.peek();
                if (f.kind == Kind.METHOD && parent != null) {
                    String body = src.substring(f.start, i + 1);
                    out.add(new Chunk(path, lineOf(src, f.start), lineOf(src, i),
                            prefix(path, stack) + body, null));
                } else if (f.kind == Kind.TYPE) {
                    emitTypeHeader(out, path, src, f, stack);
                }
                if (parent == null || parent.kind == Kind.TYPE) {
                    segStart = i + 1;
                }
            } else if (c == ';' && atTypeLevel) {
                // field, abstract method, package/import, enum constant list ...
                if (top != null) {
                    int start = firstNonBlank(src, segStart, i);
                    top.members.append("    ").append(src, start, i + 1).append('\n');
                    top.lastMemberEnd = i;
                }
                segStart = i + 1;
            }
            i++;
        }

        if (out.isEmpty()) {
            int lines = (int) src.lines().count();
            out.add(new Chunk(path, 1, lines, "// File: " + path + "\n" + src, null));
        }
        out.sort((a, b) -> Integer.compare(a.startLine(), b.startLine()));
        return out;
    }

    /** Task (7): the naive chunker -- a new chunk every {@code window} lines. */
    protected List<Chunk> chunkFixed(Path file, int window) throws IOException {
        List<String> lines = Files.readAllLines(file);
        List<Chunk> out = new ArrayList<>();
        for (int s = 0; s < lines.size(); s += window) {
            int e = Math.min(lines.size(), s + window);
            String body = String.join("\n", lines.subList(s, e));
            out.add(new Chunk(file.toString(), s + 1, e,
                    "// File: " + file + "\n" + body, null));
        }
        return out;
    }

    private void emitTypeHeader(List<Chunk> out, String path, String src, Frame type, Deque<Frame> outer) {
        int braceLine = lineOf(src, type.open);
        // type.start already points at the JavaDoc / annotations / modifiers.
        String decl = src.substring(type.start, type.open + 1);
        StringBuilder sb = new StringBuilder();
        sb.append("// File: ").append(path).append('\n');
        if (!outer.isEmpty()) {
            sb.append("// Enclosing: ").append(signatureChain(outer)).append('\n');
        }
        sb.append(decl).append('\n').append(type.members);
        if (type.members.length() > 0) sb.append("    ...\n");
        sb.append("}");
        int end = type.members.length() > 0 ? lineOf(src, type.lastMemberEnd) : braceLine;
        out.add(new Chunk(path, lineOf(src, type.start), Math.max(end, braceLine), sb.toString(), null));
    }

    private static String prefix(String path, Deque<Frame> stack) {
        return "// File: " + path + "\n// Class: " + signatureChain(stack) + "\n";
    }

    /** Outermost-first chain of enclosing type signatures, e.g. "public class A > static class B". */
    private static String signatureChain(Deque<Frame> stack) {
        List<String> sigs = new ArrayList<>();
        stack.descendingIterator().forEachRemaining(f -> {
            if (f.kind == Kind.TYPE) sigs.add(f.signature);
        });
        return String.join(" > ", sigs);
    }

    /** A '{' at type level opens a method/constructor iff its header has a parameter list and is not an initialiser. */
    private static boolean isMethodHeader(String header) {
        int paren = header.indexOf('(');
        if (paren < 0) return false;                      // static/instance initialiser block
        int eq = header.indexOf('=');
        return eq < 0 || eq > paren;                      // "x = new Foo() {" is a field
    }

    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
    }

    /** Removes annotations, collapses whitespace: "public class Loan". */
    private static String collapse(String header) {
        return header.replaceAll("@\\w+(\\([^)]*\\))?", " ").replaceAll("\\s+", " ").trim();
    }

    private static int skipTo(String s, int from, String end) {
        int j = s.indexOf(end, from);
        return j < 0 ? s.length() : j + end.length();
    }

    private static int skipLiteral(String s, int from, char quote) {
        int j = from + 1;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '\\') { j += 2; continue; }
            if (c == quote || c == '\n') return j + 1;
            j++;
        }
        return j;
    }

    private static int firstNonBlank(String s, int from, int to) {
        while (from < to && Character.isWhitespace(s.charAt(from))) from++;
        return from;
    }

    /** 1-indexed line number of offset pos. */
    private static int lineOf(String s, int pos) {
        int line = 1;
        for (int j = 0; j < pos && j < s.length(); j++) {
            if (s.charAt(j) == '\n') line++;
        }
        return line;
    }

    private enum Kind { TYPE, METHOD, OTHER }

    private static final class Frame {
        final Kind kind;
        final int start;
        final String signature;
        final StringBuilder members = new StringBuilder();
        int open;            // offset of the type's opening '{'
        int lastMemberEnd;   // offset of the last field / abstract member's ';'

        Frame(Kind kind, int start, String signature) {
            this.kind = kind;
            this.start = start;
            this.signature = signature;
        }
    }

    /**
     * Delegates to the configured {@link Embedder}; swapping the stub for
     * a real model happens at construction time (see Main), not here.
     */
    protected float[] embed(String text) {
        return embedder.embed(text);
    }

    /** Identifier of the embedding model the index was built with. */
    public String getEmbedderId() {
        return embedderId;
    }

    public List<Chunk> getIndex() {
        return index;
    }

    public int size() {
        return index.size();
    }
}
```

### `src/RagAssistant.java`

Task 4: `retrieve` and `buildPrompt`.

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query-time RAG assistant. Takes a natural-language query, embeds it,
 * pulls the top-k nearest chunks from the {@link RepoIndexer}'s index,
 * formats them into the RAG prompt template, and calls the LLM.
 *
 * Session 6A: complete {@link #retrieve(String, int)} and
 * {@link #buildPrompt(String, List)}.  Do NOT edit {@link #answer}.
 */
public class RagAssistant {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(query|context)\\}");

    private final RepoIndexer indexer;
    private final Embedder embedder;
    private final LlmClient llm;
    private final Path promptTemplate;

    public RagAssistant(RepoIndexer indexer, Embedder embedder, LlmClient llm, Path promptTemplate) {
        this.indexer = indexer;
        this.embedder = embedder;
        this.llm = llm;
        this.promptTemplate = promptTemplate;
    }

    public String answer(String query, int k) throws IOException {
        List<Chunk> hits = retrieve(query, k);
        String prompt = buildPrompt(query, hits);
        return llm.complete(prompt);
    }

    /**
     * Embeds the query, ranks every indexed chunk by cosine similarity,
     * and returns the top k (best first).
     *
     * @throws IllegalStateException if the index was built with a different
     *         embedding model than the one used for the query.
     */
    protected List<Chunk> retrieve(String query, int k) {
        if (!indexer.getEmbedderId().equals(embedder.modelId())) {
            throw new IllegalStateException("embedding model mismatch: index built with "
                    + indexer.getEmbedderId() + ", query embedded with " + embedder.modelId());
        }
        float[] q = embedder.embed(query);
        record Scored(Chunk chunk, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (Chunk c : indexer.getIndex()) {
            scored.add(new Scored(c, cosine(q, c.embedding())));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Chunk> out = new ArrayList<>();
        for (int i = 0; i < Math.min(k, scored.size()); i++) {
            out.add(scored.get(i).chunk());
        }
        return out;
    }

    /**
     * Loads the template from {@link #promptTemplate} and substitutes
     * {@code {query}} and {@code {context}}; the context is one
     * "source:start-end" header plus content block per hit, in rank order.
     * Substitution is single-pass so placeholder-looking text inside a
     * chunk or the query is never expanded.
     */
    protected String buildPrompt(String query, List<Chunk> hits) throws IOException {
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Chunk c = hits.get(i);
            ctx.append("[").append(i + 1).append("] ")
               .append(c.source()).append(':').append(c.startLine()).append('-').append(c.endLine())
               .append('\n').append(c.content().strip()).append("\n\n");
        }
        String context = ctx.toString().strip();

        String template = Files.readString(promptTemplate);
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = m.group(1).equals("query") ? query : context;
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Full cosine (does not assume unit-length vectors). */
    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("dim mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
```

### `src/Main.java`

Task 5/8: pipeline construction and the `QUESTIONS` array.

```java
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * End-to-end driver for Session 6A:
 *   1. index sample-repo/;
 *   2. for each question in {@link #QUESTIONS}, retrieve the top-k
 *      chunks and ask the LLM for an answer.
 *
 * Students add their own questions to {@link #QUESTIONS} (task 5 of
 * handson6.tex) and re-run with {@code make run}.
 *
 * The default build uses {@link StubEmbedder} + {@link StubLlmClient}
 * so the pipeline runs offline.  Once you have run
 * {@code make langchain4j} and copied {@code example/OllamaLlm.java}
 * into {@code src/}, swap the two construction lines below.
 */
public class Main {

    /**
     * Task (5): add nine more questions targeting the sample-repo/
     * library-management domain (books, members, loans, fines).
     */
    static final String[] QUESTIONS = {
        "Where is the fine for an overdue loan computed?",
        "What class holds the catalogue of books, and how is it indexed?",
        "How does the system decide a loan has been returned?",
        "What is the maximum fine the policy allows?",
        "Which methods write to the loan registry?",
        "How many books can a member have on loan at the same time?",
        "How long is the default loan term?",
        "How does the report service find overdue loans?",
        "When are two Book objects considered equal?",
        "How does searching for books by title work?",
        // Task (8): answer is NOT in the corpus -- the model must refuse.
        "Which Kafka topic does the library publish loan events to?",
    };

    private static final int TOP_K = 8;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "sample-repo");

        // -----------------------------------------------------------
        // 1. Pipeline construction.
        // -----------------------------------------------------------
        Embedder    emb = new MiniLmEmbedder();    // was: new StubEmbedder()
        LlmClient   llm = new OllamaLlm();         // was: new StubLlmClient()
        RepoIndexer idx = new RepoIndexer(emb);
        System.out.println("Embedder: " + emb.modelId() + "   LLM: " + llm
                + "   chunker: " + (System.getenv("CHUNKER") == null ? "method" : System.getenv("CHUNKER")));

        // -----------------------------------------------------------
        // 2. Index the corpus; report count + wall-clock time.
        // -----------------------------------------------------------
        Instant t0 = Instant.now();
        idx.indexDirectory(dir);
        Duration elapsed = Duration.between(t0, Instant.now());
        System.out.printf("Indexed %d chunk(s) from %s in %d ms%n%n",
                idx.size(), dir, elapsed.toMillis());

        // -----------------------------------------------------------
        // 3. Ask each question.  Print retriever hits + LLM answer.
        //    (retrieve() and answer() both re-rank; the redundancy is
        //    deliberate so the trace shows what the retriever picked.)
        // -----------------------------------------------------------
        RagAssistant ra = new RagAssistant(
                idx, emb, llm, Path.of("prompts/rag.txt"));
        for (String q : QUESTIONS) {
            System.out.println("=== Q: " + q);
            List<Chunk> hits = ra.retrieve(q, TOP_K);
            for (Chunk c : hits) {
                System.out.println("  hit: " + c.source()
                        + ":" + c.startLine() + "-" + c.endLine());
            }
            System.out.println("--- A: " + ra.answer(q, TOP_K));
            System.out.println();
        }
    }
}
```

### `prompts/rag.txt`

Task 8: the restored prompt template (lines 2–3 are the anti-hallucination guard).

```text
You are a code-search assistant answering questions about a Java repository.
Answer using ONLY the numbered passages below; do not use outside knowledge or invent names, values or APIs.
If none of the passages contains the answer, reply exactly: I do not find that in the passages.

Passages (top-K retrieved chunks):
{context}

Question: {query}

Answer in 1-3 sentences, quoting the relevant code and citing file:lines.
Answer:
```

### `src/Embedder.java`

Embedding-model id, so a model mismatch fails loudly.

```java
public interface Embedder {
    /** Return a fixed-length embedding for the given text. */
    float[] embed(String text);

    /**
     * Name + version of the embedding model. Stored alongside the index so
     * a query embedded with a different model fails loudly instead of
     * producing meaningless similarity scores.
     */
    default String modelId() {
        return toString();
    }
}
```

### `src/MiniLmEmbedder.java`

Real embedder (all-MiniLM-L6-v2).

```java
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;

/**
 * Real semantic embedder: all-MiniLM-L6-v2 (384-dim), bundled in the
 * langchain4j jar and run in-process via ONNX -- no network, no key.
 * Requires {@code make langchain4j}.
 */
public class MiniLmEmbedder implements Embedder {

    public static final String MODEL_ID = "all-MiniLM-L6-v2/langchain4j-0.36.0";

    private final EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();

    @Override
    public float[] embed(String text) {
        return model.embed(text).content().vector();
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public String toString() {
        return "MiniLmEmbedder[" + MODEL_ID + "]";
    }
}
```

### `src/OllamaLlm.java`

LLM adapter (Ollama, llama3.2:3b).

```java
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.time.Duration;

/**
 * LlmClient backed by a local Ollama daemon (see example/OllamaLlm.java).
 * Model defaults to llama3.2:3b (fits an 8 GB machine); override with
 * the OLLAMA_MODEL environment variable, e.g. OLLAMA_MODEL=llama3.1:8b.
 */
public class OllamaLlm implements LlmClient {
	private static final String MODEL =
			System.getenv().getOrDefault("OLLAMA_MODEL", "llama3.2:3b");

	private final ChatLanguageModel model = OllamaChatModel.builder()
						.baseUrl("http://localhost:11434")
						.modelName(MODEL)
						.temperature(0.0)
						.timeout(Duration.ofMinutes(3))
						.build();
	@Override
	public String complete(String prompt) {
		return model.generate(prompt);
	}

	@Override
	public String toString() {
		return "OllamaLlm[" + MODEL + "]";
	}
}
```

### `src/RetrievalEval.java`

Task 6/7: hit-rate@k (`make eval`).

```java
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
```
