import 'mdast';
import 'unist';

declare module 'mdast' {
  interface HeadingData { id?: string }
  interface Data { directiveLabel?: boolean }
}
