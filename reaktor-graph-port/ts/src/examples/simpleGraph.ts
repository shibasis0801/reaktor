import type { PortFlowGraph } from '../types';

/**
 * Simple example: Producer -> Consumer pattern
 */
export const simpleProducerConsumer: PortFlowGraph = {
    nodes: [
        {
            id: 'producer',
            type: 'portNode',
            position: { x: 0, y: 100 },
            data: {
                label: 'Data Producer',
                nodeType: 'Source',
                consumerPorts: [],
                providerPorts: [
                    { id: 'producer-out-1', key: 'data', type: 'String', isConnected: true },
                ],
            },
        },
        {
            id: 'consumer',
            type: 'portNode',
            position: { x: 300, y: 100 },
            data: {
                label: 'Data Consumer',
                nodeType: 'Sink',
                consumerPorts: [
                    { id: 'consumer-in-1', key: 'data', type: 'String', isConnected: true },
                ],
                providerPorts: [],
            },
        },
    ],
    edges: [
        {
            id: 'edge-1',
            source: 'producer',
            target: 'consumer',
            sourceHandle: 'provider-producer-out-1',
            targetHandle: 'consumer-consumer-in-1',
            type: 'smoothstep',
            data: {
                sourcePortId: 'producer-out-1',
                targetPortId: 'consumer-in-1',
                portType: 'String',
            },
        },
    ],
};

/**
 * Pipeline example: Source -> Transform -> Sink
 */
export const pipelineGraph: PortFlowGraph = {
    nodes: [
        {
            id: 'source',
            type: 'portNode',
            position: { x: 0, y: 100 },
            data: {
                label: 'API Source',
                nodeType: 'Source',
                consumerPorts: [],
                providerPorts: [
                    { id: 'source-out-raw', key: 'rawData', type: 'String', isConnected: true },
                ],
            },
        },
        {
            id: 'parser',
            type: 'portNode',
            position: { x: 250, y: 50 },
            data: {
                label: 'JSON Parser',
                nodeType: 'Transform',
                consumerPorts: [
                    { id: 'parser-in-raw', key: 'input', type: 'String', isConnected: true },
                ],
                providerPorts: [
                    { id: 'parser-out-parsed', key: 'parsed', type: 'Object', isConnected: true },
                ],
            },
        },
        {
            id: 'validator',
            type: 'portNode',
            position: { x: 250, y: 180 },
            data: {
                label: 'Schema Validator',
                nodeType: 'Transform',
                consumerPorts: [
                    { id: 'validator-in-raw', key: 'input', type: 'String', isConnected: true },
                ],
                providerPorts: [
                    { id: 'validator-out-valid', key: 'isValid', type: 'Boolean', isConnected: true },
                ],
            },
        },
        {
            id: 'combiner',
            type: 'portNode',
            position: { x: 500, y: 100 },
            data: {
                label: 'Result Combiner',
                nodeType: 'Transform',
                consumerPorts: [
                    { id: 'combiner-in-data', key: 'data', type: 'Object', isConnected: true },
                    { id: 'combiner-in-valid', key: 'isValid', type: 'Boolean', isConnected: true },
                ],
                providerPorts: [
                    { id: 'combiner-out-result', key: 'result', type: 'Object', isConnected: true },
                ],
            },
        },
        {
            id: 'sink',
            type: 'portNode',
            position: { x: 750, y: 100 },
            data: {
                label: 'Database Sink',
                nodeType: 'Sink',
                consumerPorts: [
                    { id: 'sink-in-data', key: 'data', type: 'Object', isConnected: true },
                ],
                providerPorts: [],
            },
        },
    ],
    edges: [
        {
            id: 'edge-source-parser',
            source: 'source',
            target: 'parser',
            sourceHandle: 'provider-source-out-raw',
            targetHandle: 'consumer-parser-in-raw',
            type: 'smoothstep',
            data: { sourcePortId: 'source-out-raw', targetPortId: 'parser-in-raw', portType: 'String' },
        },
        {
            id: 'edge-source-validator',
            source: 'source',
            target: 'validator',
            sourceHandle: 'provider-source-out-raw',
            targetHandle: 'consumer-validator-in-raw',
            type: 'smoothstep',
            data: { sourcePortId: 'source-out-raw', targetPortId: 'validator-in-raw', portType: 'String' },
        },
        {
            id: 'edge-parser-combiner',
            source: 'parser',
            target: 'combiner',
            sourceHandle: 'provider-parser-out-parsed',
            targetHandle: 'consumer-combiner-in-data',
            type: 'smoothstep',
            data: { sourcePortId: 'parser-out-parsed', targetPortId: 'combiner-in-data', portType: 'Object' },
        },
        {
            id: 'edge-validator-combiner',
            source: 'validator',
            target: 'combiner',
            sourceHandle: 'provider-validator-out-valid',
            targetHandle: 'consumer-combiner-in-valid',
            type: 'smoothstep',
            data: { sourcePortId: 'validator-out-valid', targetPortId: 'combiner-in-valid', portType: 'Boolean' },
        },
        {
            id: 'edge-combiner-sink',
            source: 'combiner',
            target: 'sink',
            sourceHandle: 'provider-combiner-out-result',
            targetHandle: 'consumer-sink-in-data',
            type: 'smoothstep',
            data: { sourcePortId: 'combiner-out-result', targetPortId: 'sink-in-data', portType: 'Object' },
        },
    ],
};

/**
 * Service architecture example: Multiple services with dependencies
 */
export const microservicesGraph: PortFlowGraph = {
    nodes: [
        {
            id: 'auth-service',
            type: 'portNode',
            position: { x: 0, y: 0 },
            data: {
                label: 'Auth Service',
                nodeType: 'Service',
                consumerPorts: [],
                providerPorts: [
                    { id: 'auth-out-token', key: 'tokenValidator', type: 'AuthToken', isConnected: true },
                    { id: 'auth-out-user', key: 'userInfo', type: 'UserInfo', isConnected: true },
                ],
            },
        },
        {
            id: 'user-service',
            type: 'portNode',
            position: { x: 0, y: 150 },
            data: {
                label: 'User Service',
                nodeType: 'Service',
                consumerPorts: [
                    { id: 'user-in-auth', key: 'auth', type: 'AuthToken', isConnected: true },
                ],
                providerPorts: [
                    { id: 'user-out-profile', key: 'profile', type: 'UserProfile', isConnected: true },
                ],
            },
        },
        {
            id: 'product-service',
            type: 'portNode',
            position: { x: 300, y: 0 },
            data: {
                label: 'Product Service',
                nodeType: 'Service',
                consumerPorts: [
                    { id: 'product-in-auth', key: 'auth', type: 'AuthToken', isConnected: true },
                ],
                providerPorts: [
                    { id: 'product-out-catalog', key: 'catalog', type: 'ProductList', isConnected: true },
                ],
            },
        },
        {
            id: 'order-service',
            type: 'portNode',
            position: { x: 300, y: 150 },
            data: {
                label: 'Order Service',
                nodeType: 'Service',
                consumerPorts: [
                    { id: 'order-in-auth', key: 'auth', type: 'AuthToken', isConnected: true },
                    { id: 'order-in-user', key: 'user', type: 'UserProfile', isConnected: true },
                    { id: 'order-in-product', key: 'products', type: 'ProductList', isConnected: true },
                ],
                providerPorts: [
                    { id: 'order-out-orders', key: 'orders', type: 'OrderList', isConnected: true },
                ],
            },
        },
        {
            id: 'api-gateway',
            type: 'portNode',
            position: { x: 550, y: 75 },
            data: {
                label: 'API Gateway',
                nodeType: 'Gateway',
                consumerPorts: [
                    { id: 'gateway-in-user', key: 'userInfo', type: 'UserInfo', isConnected: true },
                    { id: 'gateway-in-orders', key: 'orders', type: 'OrderList', isConnected: true },
                ],
                providerPorts: [
                    { id: 'gateway-out-api', key: 'restApi', type: 'HttpEndpoint', isConnected: false },
                ],
            },
        },
    ],
    edges: [
        {
            id: 'edge-auth-user',
            source: 'auth-service',
            target: 'user-service',
            sourceHandle: 'provider-auth-out-token',
            targetHandle: 'consumer-user-in-auth',
            type: 'smoothstep',
            data: { sourcePortId: 'auth-out-token', targetPortId: 'user-in-auth', portType: 'AuthToken' },
        },
        {
            id: 'edge-auth-product',
            source: 'auth-service',
            target: 'product-service',
            sourceHandle: 'provider-auth-out-token',
            targetHandle: 'consumer-product-in-auth',
            type: 'smoothstep',
            data: { sourcePortId: 'auth-out-token', targetPortId: 'product-in-auth', portType: 'AuthToken' },
        },
        {
            id: 'edge-auth-order',
            source: 'auth-service',
            target: 'order-service',
            sourceHandle: 'provider-auth-out-token',
            targetHandle: 'consumer-order-in-auth',
            type: 'smoothstep',
            data: { sourcePortId: 'auth-out-token', targetPortId: 'order-in-auth', portType: 'AuthToken' },
        },
        {
            id: 'edge-user-order',
            source: 'user-service',
            target: 'order-service',
            sourceHandle: 'provider-user-out-profile',
            targetHandle: 'consumer-order-in-user',
            type: 'smoothstep',
            data: { sourcePortId: 'user-out-profile', targetPortId: 'order-in-user', portType: 'UserProfile' },
        },
        {
            id: 'edge-product-order',
            source: 'product-service',
            target: 'order-service',
            sourceHandle: 'provider-product-out-catalog',
            targetHandle: 'consumer-order-in-product',
            type: 'smoothstep',
            data: { sourcePortId: 'product-out-catalog', targetPortId: 'order-in-product', portType: 'ProductList' },
        },
        {
            id: 'edge-auth-gateway',
            source: 'auth-service',
            target: 'api-gateway',
            sourceHandle: 'provider-auth-out-user',
            targetHandle: 'consumer-gateway-in-user',
            type: 'smoothstep',
            data: { sourcePortId: 'auth-out-user', targetPortId: 'gateway-in-user', portType: 'UserInfo' },
        },
        {
            id: 'edge-order-gateway',
            source: 'order-service',
            target: 'api-gateway',
            sourceHandle: 'provider-order-out-orders',
            targetHandle: 'consumer-gateway-in-orders',
            type: 'smoothstep',
            data: { sourcePortId: 'order-out-orders', targetPortId: 'gateway-in-orders', portType: 'OrderList' },
        },
    ],
};
