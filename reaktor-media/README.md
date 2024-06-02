Requirements: (these must be exposed)
1. Expose interfaces to upload images and videos
2. Expose UI to show images and stream videos
3. Expose Camera UI to capture images and videos
4. Expose Media Selector UI to upload media from device
5. Expose navigation graph
6. Show bounding box for each person
7. Associated metadata for each media
8. Allow archival to drive and everything should work

Responsibilities: (these should be internal)
1. Background upload of images and videos
2. Compression where needed
3. Basic filters using Skia
4. Trigger ML jobs where needed (uses ai module)
5. Authorized uploads by using JWT (uses auth module)
6. Handle SQL storage (uses db module)
7. Handle media caching

Assumptions: (this must be internal)
1. External modules are passed into this
2. Uses Cloudflare R2 and D1 for storage
3. No streaming as of now
4. Uses Exoplayer / AVPlayer or shows cloudflare player in a WebView

Dependencies: (these must be supplied)
1. ai module
2. auth module
3. db module
4. network module
5. background tasks module

A Pod is a Vertical Unit of Deployment
1. React UI
2. Platform Agnostic Code
3. Outlets to plug in your code
4. Navigation Graph to use functionalities
5. 
