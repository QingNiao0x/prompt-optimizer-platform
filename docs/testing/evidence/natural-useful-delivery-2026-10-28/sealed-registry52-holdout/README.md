# Registry52 isolated fresh holdout

This directory contains two new synthetic cases authored after the supplied registry52 freeze. The author did not read product source, runtime guidance, generated results, prior sealed cases, or candidate-specific strategies. No model or HTTP call was made while authoring.

All organizations, people, observations, and records in the cases are synthetic. The task types differ. Each case has a short natural original commission and two attachments totaling 800–1500 Chinese characters. Files under materials/ exactly duplicate the attachment content in cases.json; relative paths are local identifiers, not extra input.

Before the parent explicitly opens the seal, disclose only this directory's path, hashes, file/case counts, and author start/end UTC. Do not disclose the subjects, commissions, attachment content, or reviewer notes.

The complete original commission and public attachment content must be identical for all arms. review-notes.json is an independent answer card for reviewers only and must never enter enhancement or downstream model inputs. Its criteria allow multiple reasonable designs and do not prescribe a unique answer. Materials preserve facts, operating boundaries, suggestions, delegated design choices, and unknowns separately.

Frozen execution boundary supplied by the parent: same Flash model across arms, temperature 0.2, thinking disabled, stream false. Generate GEN31 and GEN52 once each with max_tokens 8192 and JSON output. Produce plain-text downstream works at max_tokens 4500 with no tools or hidden additional input. Each original/31/52 arm receives exactly two pre-numbered works per case. Preserve failures and partial responses; no quality retry. This author performs zero of those calls.

Reserved IDs: R52-HO-A-RAW-W1/W2, R52-HO-A-GEN31-W1/W2, R52-HO-A-GEN52-W1/W2; R52-HO-B-RAW-W1/W2, R52-HO-B-GEN31-W1/W2, R52-HO-B-GEN52-W1/W2. Generation IDs are R52-HO-A-GEN31/GEN52 and R52-HO-B-GEN31/GEN52. Two enhancement generations and six downstream works per case imply sixteen maximum planned calls in total, with paid review excluded unless separately authorized and counted by the parent.

The manifest hashes the payload files. The seal hashes every other file, including the manifest; a seal cannot contain its own hash, so its SHA-256 is reported separately to the parent. No external artifact, model call, or old evaluation framework is required to read these files after authorized opening.
