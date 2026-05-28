# Contributing

## Development Workflow

1. Create a feature branch from `main`.
2. Implement changes with tests first when behavior changes.
3. Run the relevant local checks:
   - `make verify`
   - `make smoke`
   - `make build`
4. Update docs when configuration, installation, or behavior changes.
5. Open a pull request with validation evidence.

## Rules

- Keep the SDK fail-open toward host applications.
- Keep Android integrations thin over the shared client/runtime.
- Keep package-family versions aligned through the BOM and matching release workflow.
