# Reaktor Documentation

The official technical manual and system specification for **Reaktor**, the Unreal Engine of App & Server Development.

This site is built with [Docusaurus](https://docusaurus.io/) and features a custom **Industrial Technical Manual** aesthetic.

## 🛠 Development

### Prerequisites

- [Node.js](https://nodejs.org/) (v18 or higher recommended)
- [npm](https://www.npmjs.com/)

### Installation

Navigate to the project directory and install dependencies:

```bash
npm install
```

### Local Development

Start the development server:

```bash
npm start
```

The documentation will be available at `http://localhost:3000`.

## 🏗 Build & Validation

To generate a production build:

```bash
npm run build
```

The output will be in the `build/` directory.

### Standards

This project adheres to the standards defined in [GEMINI.md](./GEMINI.md). Please ensure all MDX compilation errors are resolved before committing.

## 🎨 Visual System

The site uses a custom "Technical Blueprint" design system:
- **Typography**: VT323 (Monospace), Lora (Serif), Space Mono (Technical).
- **Theme**: Supports both Light (Blueprint Paper) and Dark (Negative Blueprint) modes.
- **Components**: Includes interactive `<Blueprint />` diagrams with hoverable hotspots.

## 🚀 Deployment

The site is optimized for deployment on **Cloudflare Pages** or **GitHub Pages**.

### Cloudflare Pages (Recommended)

```bash
npx wrangler pages deploy build --project-name=reaktor-docs
```

### GitHub Pages

```bash
GIT_USER=<your-username> npm run deploy
```

---

*Prepared for Bestbuds Ventures — March 2026*
