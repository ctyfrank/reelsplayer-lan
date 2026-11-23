# Credits and third-party notices

ReelsPlayer LAN is a modified derivative. This file lists what it is built on, what was taken from
where, and the license notices that must travel with the code.

## Derived from

### ReelsPlayer — https://github.com/ctyfrank/reelsplayer

Base application: vertical swipe feed, local (SAF) folder playback, player UI, project structure.
`MainActivity`, `VideoAdapter`, `activity_main.xml`, `item_video.xml` and the base strings were taken
from this project and modified (LAN entry point, player pooling, prefetch, tuned load control).

> MIT License
>
> Copyright (c) 2026 underwear
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

## Referenced (no source copied)

### CIFS Documents Provider — https://github.com/wa2c/cifs-documents-provider

Used as the reference implementation for jcifs-ng on Android: SMB version negotiation properties,
NTLM / guest / anonymous context setup, timeouts and connection caching. The SMB code in this project
was written separately, but the configuration approach follows this project.

> MIT License
>
> Copyright (c) 2020 wa2c

(The full MIT text is identical to the ReelsPlayer notice above.)

## Runtime dependencies

| Package | License | Link |
|---|---|---|
| `eu.agno3.jcifs:jcifs-ng` 2.1.10 | LGPL-2.1 | https://github.com/AgNO3/jcifs-ng |
| `org.bouncycastle:bcprov-jdk18on` 1.76 | Bouncy Castle License (MIT-style) | https://www.bouncycastle.org/java.html |
| `androidx.media3:media3-exoplayer` / `media3-ui` / `media3-datasource` / `media3-database` 1.2.0 | Apache-2.0 | https://github.com/androidx/media |
| `androidx.appcompat:appcompat` 1.6.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `com.google.android.material:material` 1.11.0 | Apache-2.0 | https://github.com/material-components/material-components-android |
| `androidx.viewpager2:viewpager2` 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.documentfile:documentfile` 1.0.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `org.slf4j:slf4j-api` / `slf4j-nop` 1.7.36 | MIT | https://www.slf4j.org/ |

### About the LGPL dependency

`jcifs-ng` is licensed under the GNU Lesser General Public License, version 2.1. It is linked as an
unmodified library and is not statically merged into this project's own code. The corresponding
source is available at the link above. If you redistribute an APK built from this repository, keep
this notice and make the library's source available as required by the LGPL.