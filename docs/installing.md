
# Scheduler installation

## Requirements

Experimaestro is implemented using several languages and thus, you
have to install multiple ecosystem with the right version.

### Python 3

You need a working `python3` interpreter on your system along with `pip3`.

To install required python's modules type:

```sh
pip3 install -r server/scripts/requirements.txt
```

### Java (≥ 8)

You need a working *Java* installation (version ≥ 1.8) on your machine. [Gradle](https://gradle.org) is
used for building but is not a requirement.

On linux or OS X:
```sh
# From the project's root directory.
./gradlew installDist
```

On Windows:
```bat
gradlew.bat installDist
```

This will build and install all the dependencies into `server/build/install/experimaestro-server`. The command `experimaestro` is located in `server/build/install/experimaestro-server/bin/experimaestro`.

Note that on a Linux box if you are not root and the alternative link
is not set to the proper version (you can inspect that using
`update-alternatives --display java`), you won't have the permission
to update the link manually using `update-alternatives --config java`.
The work around is to set the environment variable `JAVA_HOME` when
calling maven. For example:

To locate the path of to set to `JAVA_HOME` you can do `locate jdk | less`.

  The server and clients are configured by a simple property file `settings.ini`, located in the `.experimaestro` (by default) file in the user's home directory.

## Shell completion

To enable shell completion follow instructions in
[argcomplete](https://pypi.python.org/pypi/argcomplete) documentation.

### ZSH

Add to `.zshrc`:

```sh
  # Register python completion
  if type register-python-argcomplete &> /dev/null
  then
    eval "$(register-python-argcomplete 'experimaestro')"
  fi
```

* Re-launch your shell: `exec zsh`

## Configuration

A configuration file must be stored in `$HOME/.experimaestro/settings.json`

```json
{
    "server": {
        "name": "[name of the server]",
        "port": 12345,
        "database": "[path to the database]",
        "passwords": [
            {
                "user": "[username]",
                "password": "[plain text password]",
                "roles": [ "user" ]
            }
        ]
    },

    "hosts": {
        "local": {
            "host": "localhost",
            "port": 12345,
            "username": "XXXX",
            "password": "XXXX"
        }
    },
}
```

The `server` section contains settings for the experimaestro server, while
the `hosts` section is used to access easily several experimaestro servers
by giving them an ID (here `local`). You can several others.


## Test your installation

To test your installation is working properly you can try to start a
server like this:


```sh
experimaestro --verbose --debug start-server
```

You should get something like:

```txt
INFO:root:Starting with gradle
INFO:root:Waiting for server to start (PID=37851)...
INFO:root:Server started...
```