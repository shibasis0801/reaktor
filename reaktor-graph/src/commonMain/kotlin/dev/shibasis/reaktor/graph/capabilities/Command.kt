package dev.shibasis.reaktor.graph.capabilities


/**
 * Using gRPC semantics, https://grpc.io/docs/what-is-grpc/core-concepts/#service-definition
 * gRPC lets you define four kinds of service method:
 *
 * Unary RPCs where the client sends a single request to the server and gets a single response back, just like a normal function call.
 *
 * rpc SayHello(HelloRequest) returns (HelloResponse);
 * Server streaming RPCs where the client sends a request to the server and gets a stream to read a sequence of messages back. The client reads from the returned stream until there are no more messages. gRPC guarantees message ordering within an individual RPC call.
 *
 * rpc LotsOfReplies(HelloRequest) returns (stream HelloResponse);
 * Client streaming RPCs where the client writes a sequence of messages and sends them to the server, again using a provided stream. Once the client has finished writing the messages, it waits for the server to read them and return its response. Again gRPC guarantees message ordering within an individual RPC call.
 *
 * rpc LotsOfGreetings(stream HelloRequest) returns (HelloResponse);
 * Bidirectional streaming RPCs where both sides send a sequence of messages using a read-write stream. The two streams operate independently, so clients and servers can read and write in whatever order they like: for example, the server could wait to receive all the client messages before writing its responses, or it could alternately read a message then write a message, or some other combination of reads and writes. The order of messages in each stream is preserved.
 *
 * rpc BidiHello(stream HelloRequest) returns (stream HelloResponse);
 *
 */

//sealed class Command<Request, Response> {
//    data class UnarySync<Request, Response>(
//        val request: Request,
//        val response: Response
//    ): Command<Request, Response>()
//
//    data class Unary<Request, Response>(
//        val request: Request,
//        val response: CompletableDeferred<Response>
//    ): Command<Request, Response>()
//
//    data class ResponseStream<Request, Response>(
//        val request: Request,
//        val response: Channel<Response>
//    ): Command<Request, Response>()
//
//    data class RequestStream<Request, Response>(
//        val request: Channel<Request>,
//        val response: CompletableDeferred<Response>
//    ): Command<Request, Response>()
//
//    data class BidirectionalStream<Request, Response>(
//        val request: Channel<Request>,
//        val response: Channel<Response>
//    ): Command<Request, Response>()
//}
//
//sealed class DirectedEdge<Request, Response>(
//    val start: Node,
//    val end: Node,
//    val outgoing: MutableSharedFlow<Command<Request, Response>> = MutableSharedFlow(),
//): Unique by UniqueImpl() {
//
//    suspend fun unary(request: Request): Response {
//        val response = CompletableDeferred<Response>()
//        val command = Command.Unary(request, response)
//        outgoing.emit(command)
//        return response.await()
//    }
//
//    suspend fun responseStream(request: Request): Channel<Response> {
//        val response = Channel<Response>()
//        val command = Command.ResponseStream(request, response)
//        outgoing.emit(command)
//        return response
//    }
//
//    suspend fun requestStream(request: Channel<Request>): Response {
//        val response = CompletableDeferred<Response>()
//        val command = Command.RequestStream(request, response)
//        outgoing.emit(command)
//        return response.await()
//    }
//
//    suspend fun responseStream(request: Channel<Request>): Channel<Response> {
//        val response = Channel<Response>()
//        val command = Command.BidirectionalStream(request, response)
//        outgoing.emit(command)
//        return response
//    }
//}
//
//class UnaryDirectedEdge<Request, Response>(
//    start: Node, end: Node
//): DirectedEdge<Request, Response>(start, end)
//
//class DuplexDirectedEdge<
//        ForwardRequest, ForwardResponse,
//        ReverseRequest, ReverseResponse>(
//    start: Node,
//    end: Node,
//    val incoming: MutableSharedFlow<Command<ReverseRequest, ReverseResponse>> = MutableSharedFlow()
//): DirectedEdge<ForwardRequest, ForwardResponse>(start, end) {
//    suspend fun unaryReverse(request: ReverseRequest): ReverseResponse {
//        val response = CompletableDeferred<ReverseResponse>()
//        val command = Command.Unary(request, response)
//        incoming.emit(command)
//        return response.await()
//    }
//
//    suspend fun responseStreamReverse(request: ReverseRequest): Channel<ReverseResponse> {
//        val response = Channel<ReverseResponse>()
//        val command = Command.ResponseStream(request, response)
//        incoming.emit(command)
//        return response
//    }
//
//    suspend fun requestStreamReverse(request: Channel<ReverseRequest>): ReverseResponse {
//        val response = CompletableDeferred<ReverseResponse>()
//        val command = Command.RequestStream(request, response)
//        incoming.emit(command)
//        return response.await()
//    }
//
//    suspend fun responseStreamReverse(request: Channel<ReverseRequest>): Channel<ReverseResponse> {
//        val response = Channel<ReverseResponse>()
//        val command = Command.BidirectionalStream(request, response)
//        incoming.emit(command)
//        return response
//    }
//}
//
