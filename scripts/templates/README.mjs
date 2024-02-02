import {trim} from "../helpers.mjs";

export const README = (moduleName) => trim`
Requirements: (these must be exposed)
1. Expose interfaces to upload images and videos
2. Expose UI to show images and stream videos
3. Expose Camera UI to capture images and videos
4. Expose Media Selector UI to upload media from device
5. Handle internal navigation
6. Show bounding box for each person
7. Associated metadata for each media

Responsibilities: (these should be internal)
1. Background upload of images and videos
2. Compression where needed
3. Basic filters using Skia
4. Trigger ML jobs where needed (uses ai module)
5. Authorized uploads by using JWT (uses auth module)
6. Handle SQL storage (uses db module)


Assumptions: (this must be internal)
1. External modules are passed into this
2. Uses Cloudflare R2 and D1 for storage
3. No streaming as of now
4. Uses Exoplayer / AVPlayer or shows cloudflare player in a WebView

`