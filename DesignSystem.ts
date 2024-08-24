/*
Design system should take best from compose, react and material design.
Should be closer to compose without the padding nonsense.
At runtime, these styles will get converted into emotion-js / compose modifiers / stylesheet.
Caching for conversions would be needed.
It can't match native pure css in speed, but we need to make it close with Server Components.

Everything is a flexbox as grid is not supported in react native or jetpack compose.
*/

// FlexBox
type Box = {
    width: string,
    height: string,

    color: string,

    elevation: string,
    shadow: string,

    margin: string,
    padding: string,

    borderWidth: string,
    borderRadius: string,
    borderColor: string
}

type Typography = Box & {
    fontSize: string,
    fontWeight: string,
    lineHeight: string,
    letterSpacing: string,
    textAlign: string
}

type Button = Box & {
    hoverColor: string,
    activeColor: string,
    focusColor: string,
    disabledColor: string,
    content: Box
}
