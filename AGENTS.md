# Fluence Android — Engineering Guidance

## Repository Documentation

This repository contains canonical documentation under `/docs`.

Agents must consult the relevant document before modifying a subsystem.

Examples:

- docs/RELEASE_PIPELINE.md
- docs/ARCHITECTURE.md
- docs/SYNC.md
- docs/DESIGN_SYSTEM.md

Do not duplicate documentation inside AGENTS.md.

## Release Pipeline

The Android release pipeline is considered **frozen**.

Do not modify signing, GitHub Actions release logic, or release infrastructure unless fixing a verified production issue.

See: [docs/RELEASE_PIPELINE.md](docs/RELEASE_PIPELINE.md)
