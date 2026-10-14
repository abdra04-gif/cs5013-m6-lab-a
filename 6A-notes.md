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
| Starter stub chunker (one chunk per file) + `StubEmbedder` | **8** | 11 ms |
| Method chunker + `MiniLmEmbedder` | **51** (42 methods/ctors + 9 type headers) | 401 ms |
| 50-line chunker + `MiniLmEmbedder` (task 7) | 12 | 185 ms |

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
