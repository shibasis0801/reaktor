# Reaktor Documentation Project: Gemini Guidelines

This project uses Docusaurus to document Reaktor, a Kotlin Multiplatform framework. To maintain the highest quality and architectural integrity, adhere to the following mandates.

## 1. Documentation Quality & Standards

- **Clarity First**: Every page must start with a high-level summary of the concept.
- **Visual Aids**: When describing graph-based concepts (Mesh, Actors, UI), describe how these are visualized in the Blueprint editor.
- **Accurate Terminology**: Always use established Reaktor terms (e.g., `ProviderPort`, `ConsumerPort`, `AgentOperatorNode`, `FlexBuffer`, `ObjectStore`).
- **Markdown Purity**: Avoid raw HTML unless strictly necessary. Use Docusaurus-native components (Admonitions, Tabs, Code blocks) for complex layouts.
- **MDX Safety**: Escape all angle brackets (`<`, `>`) that are not part of a JSX component. This is critical in tables and code snippets to avoid MDX compilation errors.

## 2. Engineering Standards

- **Consistent Structure**: Maintain the established folder structure (`architecture`, `features`, `data`, `frontend`, `roadmap`, `strategy`, `resources`).
- **Reference Accuracy**: Ensure all internal links are valid and cross-referenced accurately.
- **Type Safety**: When providing code examples (Kotlin, TS, Dart, Swift, C++), ensure they follow the idiomatic patterns described in the strategic assessment.

## 3. Build & Validation

- **No Broken Links**: Use `npm run build` to verify that there are no broken links.
- **MDX Verification**: Before finalizing any change, verify that the MDX loader succeeds.
- **Formatting**: Adhere to the project's Prettier and ESLint configurations.

## 4. Error Handling

- **Fix Build Errors First**: If the project fails to build, do not add new content until the build is fixed.
- **Root Cause Analysis**: For every MDX error, identify the specific character or sequence causing the failure and apply a fix that maintains readability.

## 5. Architectural Alignment

- **Reaktor Vision**: Always align documentation with the vision of Reaktor as the "Unreal Engine of App Development."
- **Distributed First**: Emphasize Reaktor's capabilities in distributed systems, P2P mesh, and distributed actors.
- **Agentic Operations**: Treat AI agents as first-class citizens in all documentation.
