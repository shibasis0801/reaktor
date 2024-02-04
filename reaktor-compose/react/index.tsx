import * as React from "react";
import {
    TouchableOpacity,
    StyleSheet,
    GestureResponderEvent,
    View
} from "react-native";
import { Card, Text, Title, Paragraph, Button } from 'react-native-paper';

export interface ButtonProps {
    text: string;
    onClick?: (event: GestureResponderEvent) => void;
}


export const MyCard = () => (
    <Card mode="contained">
        <Card.Cover source={{ uri: 'https://picsum.photos/700' }} />
        <Card.Content>
            <Text variant="titleLarge">
                shibasis
            </Text>
            <Text variant="bodyMedium">
                My computer
            </Text>
        </Card.Content>
        {/*<Card.Actions>*/}
        {/*    <Button>Cancel</Button>*/}
        {/*    <Button>Ok</Button>*/}
        {/*</Card.Actions>*/}
    </Card>
);

export function helloWorld() {
    console.log("HELLO FROM core")
}

export function data() {
    return "SHIBAISS"
}
