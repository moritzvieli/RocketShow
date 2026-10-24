# Rocket Show

Rocket Show is a system to automate and play shows including audio, video, lighting (e.g. DMX) and MIDI on Raspberry Pi
devices.

Check our website: https://rocketshow.net

## Usage

Refer to [the docs](./docs/index.md) to find out how to use Rocket Show.

## Development

### Build

1. Build: `./mvnw clean package`
2. Start: `java -jar target/rocketshow.jar`
3. Open the web app on http://localhost:8080

Use this command to skip tests and NPM build:
```shell
./mvnw package -Dskip.npm -Dmaven.test.skip=true && java -jar target/rocketshow.jar
```

For frequent builds, you might want to comment out the frontend-maven-plugin in the POM and make use of the Maven
parameter `-DskipTests`.

### Testing

See [testing/README.md](./testing/README.md) for the test strategy (unit tests, device self-test and the
hardware-in-the-loop rig) and the open todos.

### Local Angular frontend development

While developing the web app, it might be convenient to start an Angular server:

1. Add this entry to your hosts file (e.g. /etc/hosts on the Mac) to connect to a rocketshow.local server: `127.0.0.1 app.rocketshow.local`. If you connect to another device not behind mDNS rocketshow.local, you need to add the IP with rocketshow.local as well.
2. Install npm packages (force required because some dependency conflicts): `cd src/main/webapp && npm install --force`
3. Start the server with a local backend `npx serve` or a device backend `ng serve --host app.rocketshow.local --port 4200`
4. Open the web application: http://localhost:4200

On the Mac, Gstreamer and OLA can be installed using Homebrew:

### Debugging

To debug Gstreamer issues, export GST_DEBUG before starting the server:

```shell
export GST_DEBUG=3
```

Pipelines can be tested using gst-launch-1.0. E.g.:

```shell
gst-launch-1.0 videotestsrc ! videoconvert ! autovideosink
gst-launch-1.0 uridecodebin uri=file:///opt/rocketshow/media/video/clouds.mp4 ! queue ! kmssink
gst-launch-1.0 uridecodebin uri=file:///opt/rocketshow/media/audio/head_smashed_far_away.wav ! audioconvert ! audioresample ! "audio/x-raw,rate=44100" ! alsasink device=rocketshow
```

To test audio playback with alsa:

```shell
aplay -D rocketshow /opt/rocketshow/media/audio/head_smashed_far_away.wav
```

```shell
brew install gstreamer
brew install gst-plugins-base
brew install gst-plugins-good
brew install gst-plugins-bad
brew install gst-plugins-ugly
brew install ola
```

A few testpipelines for Mac:

```shell
gst-launch-1.0 videotestsrc ! videoconvert ! osxvideosink
gst-launch-1.0 uridecodebin uri=file:///opt/rocketshow/media/video/clouds.mp4 ! queue ! osxaudiosink
```

Launch the OLA daemon on Mac:

```shell
olad
```

Launch Rocket Show on the mac:

```shell
./start.sh
```

### Health check and system test

Use the following HTTP endpoint to check the health (available without security): `GET /api/system/health`

Use the following HTTP endpoint to run some system tests (available without security, as long as the initial setup is not yet completed): `POST /api/system/test`

### Build OLA

To build the OLA Client jar required by Rocket Show, follow these steps on a mac:

```
# install protobuf 21 (newer versions of protobuf don't work by OCT 2024, because of too old cpp versions used in dependencies, see https://github.com/protocolbuffers/protobuf/issues/12393)
brew install protobuf@21 autoconf automake libtool cppunit

echo 'export PATH="/opt/homebrew/opt/protobuf@21/bin:$PATH"' >> ~/.zshrc
export LDFLAGS="-L/opt/homebrew/opt/protobuf@21/lib"                
export CPPFLAGS="-I/opt/homebrew/opt/protobuf@21/include"
export PKG_CONFIG_PATH="/opt/homebrew/opt/protobuf@21/lib/pkgconfig"

autoreconf -i
./configure --enable-java-libs
make -j$(nproc)
```

## Deployment

### Raspberry Pi Image building

Building is recommended on a Raspberry Pi device with enough storage. Steps to follow:

- Build the application (see [Build](#build))
- Switch to user `root`:

````shell
sudo su - root
````

- Update apt:

````shell
apt-get update
````

- Prepare the environment according to [https://github.com/RPi-distro/pi-gen](pi-gen Readme) (e.g. install the required
  dependencies)
- Copy these files to `/root/` on the build server:
  - `rocketshow.jar` (the application built above)
  - `dist/install/build-community.sh`
  - `dist/install/raspbian-community.sh`
  - `dist/install/black.jpg`
  - `designer_template.json` (from `dist/designer_template.json`)
  - Tar gz `dist/defaults` and copy `defaults.tar.gz` to `/root/` on the build server
- Make the scripts executable

````shell
chmod +x /root/build-community.sh /root/raspbian-community.sh
````

- Change into a directory with enough free space and run the build script (might take about 45 minutes):

````shell
bash /root/build-community.sh
````

- Grab the image ZIP `<date>-RocketShow-community.zip` in directory `/root`

The image is built in the `build` subdirectory of the current directory.

#### Optional

- Copy dist/install/*.sh scripts to rocketshow.net/install/script/*.sh, if updated
- Copy dist/before.sh, dist/after.sh to rocketshow.net/update/xy.sh, if updated
- Copy the new complete image to rocketshow.net/install/images and change the file latest.php to link the new version
- Copy seed directory directory.tar.gz to rocketshow.net/install, if updated

### Application

The built application should be uploaded to rocketshow.net/update and be named "rocketshow.jar". The file "
currentversion2.xml" can be modified accordingly.

## Code structure

### Server

#### Overview

The Rocket Show server is written in Java and uses Spring
Boot ([https://spring.io/projects/spring-boot](https://spring.io/projects/spring-boot)).
Gstreamer ([https://gstreamer.freedesktop.org/](https://gstreamer.freedesktop.org/)), a framework written in C, is
included as multimedia playback backend.

Spring services are autowired into each other (dependency injection). The interface is named XyService, the
corresponding implementation is called DefaultXyService.

The class RocketShowApplication serves as the application entry point. Some beans are initialized in the correct order.

There is a player service, which organises all composition players (responsible for the playback of a single
composition). Parallel playbacks are possible as well.

The code is structured in different modules, which are described in more details below.

##### Base functionalities

A few base services and models lie in the root folder (e.g. settings, session-handling).

##### Api

This module is responsible for the communication with the web app and with other Rocket Show devices. A couple of REST
interfaces are exposed as well as some web sockets for time critical topics or where server push is required.

##### Audio

Services related to audio playback.

The Gstreamer audio pipeline looks like this:
```
uridecodebin
-> audioconvert mix-matrix=...
-> audioresample
-> audiomixer
-> capsfilter audio/x-raw,rate=48000,channels=2
(-> level)
-> queue
-> volume
-> alsasink
```

##### Composition

Handling the composition and the composition player.

##### Gstreamer

Rocket Show specific calls to the native Gstreamer C api.

##### Image

Handling the image displaying.

##### Lighting

Responsible for the connection of Rocket Show to the Open Lighting Architecture to control connected lighting
interfaces. Services for designer project playback also lies here.

The sources of the inluded jar file with the OLA client are copied from an archive into ola-java-client-src.

##### MIDI

MIDI input, output routing and mapping.

##### Raspberry

Raspberry Pi specific services (e.g. GPIO triggers). Check the Raspberry Pi GPIO state like this:
```shell
gpioget -c gpiochip0 24
```

Where active = high and inactive = low.

##### Scheduler

Starts compositions based on a timer. A scheduled composition either repeats after a fixed amount of time (e.g. every
5 minutes) or is started at a specific time of the day, daily, on selected weekdays, monthly or yearly. The scheduled
compositions are stored in the settings and are planned again as soon as they are changed.

##### Util

Various utilities used across the project.

### Web app

TODO