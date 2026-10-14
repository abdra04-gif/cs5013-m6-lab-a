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
