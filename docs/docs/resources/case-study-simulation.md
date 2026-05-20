# Case Study: Cortical Column Simulation

Demonstrating the full power of Reaktor's stack—including distributed actors, C++ Highway SIMD, Reaktor Mesh, and the Blueprint editor—is the **100,000-neuron cortical column simulation**.

## Architecture & Components

| Component | Implementation |
| --- | --- |
| **SpikeNeuronActor** | Models the LIF/Izhikevich neuron. Uses **C++ Highway SIMD** for vectorized weight accumulation across synapses. Receives and fires spike messages via typed ports. |
| **SynapseEdge** | A weighted edge with **STDP (Spike-Timing-Dependent Plasticity)**. Uses C++ SIMD for exponential decay calculation during Hebbian learning. |
| **ColumnSupervisorActor** | Supervises a single cortical layer. Detects pathological synchronization, modulates inhibition, and reports telemetry to the Blueprint editor. |
| **SimulationCoordinator** | The global clock for the simulation, broadcasting ticks every 0.1–1ms and managing checkpointing to `ObjectStore`. |

## Distributed Simulation

-   **Horizontal Scaling**: Each mobile phone in the mesh hosts 1,000–5,000 neurons, while server-side actors host the coordinator and heavily connected neurons.
-   **Intra-device Communication**: Uses in-process Channels with sub-microsecond latency.
-   **Inter-device Communication**: Uses the **Reaktor Mesh** with unreliable `DataChannels` (1–10ms latency), matching biological noise tolerance.
-   **Visualization**: The Blueprint editor visualizes the simulation in real-time. Neurons are colored by their firing rate, edges by synaptic weight, and the system displays real-time spike rasters.

## Why Reaktor for Neural Simulations?

This simulation proves a key architectural insight: **neural network implementations do not require custom infrastructure**. They are simply actors sending typed messages through the graph over the Mesh. The same runtime that powers a social chat application also powers a distributed neural simulation, with the Blueprint editor providing the same high-level visualization for both.
