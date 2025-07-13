package dev.shibasis.reaktor.ffi.transport

import dev.shibasis.reaktor.ffi.payload.FlexPayload
import dev.shibasis.reaktor.core.framework.Adapter
import kotlinx.coroutines.flow.Flow

abstract class Transport<Controller>(
    controller: Controller
): Adapter<Controller>(controller) {
    fun interface SyncResponse {
        fun sendSync(payload: FlexPayload): FlexPayload
    }

    fun interface AsyncResponse {
        fun sendAsync(payload: FlexPayload): Flow<FlexPayload>
    }

    fun interface ReceiveSingle {
        fun onReceiveSingle(payload: FlexPayload)
    }

    fun interface ReceiveFlow {
        fun onReceiveFlow(payload: FlexPayload): Flow<FlexPayload>
    }
}



object ByteBufferTransport:
    Transport<Unit>(Unit),
    Transport.SyncResponse,
    Transport.AsyncResponse,
    Transport.ReceiveSingle,
    Transport.ReceiveFlow {
    override fun sendSync(payload: FlexPayload): FlexPayload {
        TODO("Not yet implemented")
    }

    override fun sendAsync(payload: FlexPayload): Flow<FlexPayload> {
        TODO("Not yet implemented")
    }

    override fun onReceiveSingle(payload: FlexPayload) {
        TODO("Not yet implemented")
    }

    override fun onReceiveFlow(payload: FlexPayload): Flow<FlexPayload> {
        TODO("Not yet implemented")
    }
}

// transmission vs transport



interface CalculatorModule {
    fun sum(a: Int, b: Int): Int
    fun sequence(start: Int, end: Int, step: Int, timeInterval: Int): Flow<Int>
}

/*

1. Build async flow in C++ without libraries.

struct CalculatorModule {
        int sum(int a, int b);
        shared_ptr<Flow> sequence(int start, int end, int step, int timeInterval);
}

struct CalculatorModule {
        int sum(int a, int b) { return a + b; }
        shared_ptr<Flow> sequence(int start, int end, int step, int timeInterval) {
            auto flow = make_shared<Flow>();
            int current = start;

            thread([flow, current, end, step, timeInterval]() {
                while (current <= end) {
                    flow->emit(current);
                    current += step;
                    this_thread::sleep_for(std::chrono::milliseconds(timeInterval));
                }
                flow->finish();
            }).detach();

            return flow;
        }
}

struct Flow {
    std::vector<std::function<void(int)>> observers;
    void collect(std::function<void(int)> observer) { observers.push_back(observer); }
    void emit(int value) { for (auto& observer : observers) observer(value); }
    void finish() { for (auto& observer : observers) observer(-1); }
};

*/




