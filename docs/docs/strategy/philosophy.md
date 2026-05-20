# Philosophy: Lessons from Unreal Engine

Reaktor's vision is not just to be a framework, but an engine. This means adopting the same discipline and design principles that made Unreal Engine dominant in its field.

## 1. Eat Your Own Dogfood Aggressively

Unreal's relationship with *Fortnite* is the single most important factor in its dominance. Every feature is battle-tested in a live product serving millions of users before being documented as stable. Reaktor follows this same discipline:

-   **Battle-Tested**: Every new Reaktor feature must ship in a flagship product like **Bestbuds** or **Manna** before being declared stable.
-   **No Frozen Forks**: Every consulting project must use the latest Reaktor version, ensuring real-world feedback on the latest changes.
-   **Production First**: Bug reports from production applications always take priority over new feature requests.

## 2. The 30-Second Rule

A developer evaluating a framework will decide in 30 seconds. Reaktor aims to provide:

-   **Instant Pitch**: A landing page with a clear 30-second pitch and animated graph visualization.
-   **A Playground**: A browser-based playground where developers can modify a Reaktor graph and see results instantly.
-   **5-Minute Tutorial**: A getting-started guide that produces a working cross-platform app in under five minutes.

## 3. Make the Graph Visible

Unreal's **Blueprints** visual scripting system made the engine accessible to non-programmers. Reaktor's equivalent is the **Blueprint editor**, which must be:

-   **Interactive**: Click any node to see its live state, ports, and active edges.
-   **Live**: Connected to a running application, updating in real-time as the graph evolves.
-   **Debuggable**: Set breakpoints on edges and inspect the data flowing through ports.
-   **Editable**: Eventually, Reaktor will support full drag-and-drop graph construction.

## 4. Free Core, Paid Everything Else

Unreal is free to download, with a royalty model for successful products. Reaktor's model is similarly designed to lower barriers to entry while ensuring sustainability:

-   **Free**: The engine layer, platform services, CLI, documentation, and community support.
-   **Paid**: Managed hosting (**Reaktor Cloud**), enterprise support SLAs, custom module development, and certification programs.
-   **Revenue Share**: A micro-royalty model for applications exceeding a high revenue threshold.
