# Contributing

Thank you for your interest in contributing! This guide covers everything you need to get started.

## Table of Contents

- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Development Setup](#development-setup)
- [Making Changes](#making-changes)
  - [Branching Strategy](#branching-strategy)
  - [Code Style](#code-style)
  - [Commit Messages](#commit-messages)
  - [Running Tests](#running-tests)
- [Submitting a Pull Request](#submitting-a-pull-request)
- [Reporting Issues](#reporting-issues)
- [Project Architecture](#project-architecture)
- [Legal](#legal)

## Getting Started

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| [Java](https://adoptium.net/) | **21+** | LTS recommended |
| [Gradle](https://gradle.org/) | **9.4+** | Or use the included `gradlew` wrapper |
| [Git](https://git-scm.com/) | **2.x+** | For version control |

### Development Setup

1. **Fork** the repository on GitHub.

2. **Clone** your fork locally:

   ```bash
   git clone https://github.com/<your-username>/<repo>.git
   cd <repo>
   ```

3. **Build** the project to verify your setup:

   ```bash
   ./gradlew build
   ```

> [!TIP]
> You do not need to install Gradle separately. The included `gradlew` wrapper script will download the correct version automatically.

## Making Changes

### Branching Strategy

All development is based on the `master` branch.

1. Create a feature branch from `master`:

   ```bash
   git checkout -b feature/your-feature master
   ```

2. Make your changes in small, focused commits.

3. Keep your branch up to date with `master`:

   ```bash
   git fetch origin
   git rebase origin/master
   ```

### Code Style

- Follow standard Java conventions and the existing code patterns in the project.
- Use [Lombok](https://projectlombok.org/) annotations where the project already does (e.g., `@Getter`, `@Builder`, `@RequiredArgsConstructor`).
- Use [JetBrains Annotations](https://github.com/JetBrains/java-annotations) (`@NotNull`, `@Nullable`) for nullability contracts on public APIs.
- Write Javadoc for all public classes and methods.
- Omit braces on single-line control flow bodies; use braces when the body wraps to multiple lines.

### Commit Messages

Write commit messages in **imperative mood** (e.g., "Add rate limiter" not "Added rate limiter"):

```
Add support for custom decoders

Introduce a pluggable decoder interface that allows consumers
to register custom response body decoders alongside the default
Gson-based implementation.
```

- Keep the subject line under 72 characters.
- Separate the subject from the body with a blank line.
- Use the body to explain *what* and *why*, not *how*.

### Running Tests

Run the full test suite:

```bash
./gradlew test
```

Run a specific test class:

```bash
./gradlew test --tests "dev.simplified.*.SomeTest"
```

> [!NOTE]
> All tests must pass before a pull request can be merged. If the project does not yet have tests in the area you changed, consider adding them.

## Submitting a Pull Request

1. Push your branch to your fork:

   ```bash
   git push origin feature/your-feature
   ```

2. Open a **Pull Request** against the `master` branch of the upstream repository.

3. In the PR description:
   - Describe what the change does and why.
   - Reference any related issues (e.g., `Closes #42`).
   - Note any breaking changes.

4. Respond to review feedback and make requested changes.

## Reporting Issues

- Use [GitHub Issues](../../issues) to report bugs or request features.
- Include steps to reproduce for bug reports.
- Include the Java version, OS, and Gradle version when reporting build issues.

## Project Architecture

This project follows a modular package structure under the `dev.simplified` namespace. Each package is focused on a single responsibility. The build is a single-module Gradle project using the `java-library` plugin.

Key conventions:
- **Gradle Kotlin DSL** for build configuration (`build.gradle.kts`)
- **Version catalog** for dependency management (`gradle/libs.versions.toml`)
- **JitPack** for artifact publishing
- Internal Simplified-Dev libraries are consumed as `api` dependencies from JitPack

## Legal

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE.md), the same license that covers this project.
