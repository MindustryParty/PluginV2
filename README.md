### Building a Jar

`gradlew jar` / `./gradlew jar`

Output jar should be in `build/libs`.

### Installing

Simply place the output jar from the step above in your server's `config/mods` directory and restart the server.
Then, make your config changes in `mppv2.properties` and restart the server again.

### Database setup

To initialize the database, run this SQL query:

```
CREATE TABLE `players` (
  `uuid` varchar(100) NOT NULL,
  `playtime_vanilla` int(50) NOT NULL DEFAULT '0',
  `playtime_modded` int(50) NOT NULL DEFAULT '0',
  `playtime_total` int(50) NOT NULL DEFAULT '0',
  `name` varchar(100) NOT NULL DEFAULT 'UNKNOWN PLAYER',
  `rank` varchar(50) NOT NULL DEFAULT 'default',
  `id` INT AUTO_INCREMENT PRIMARY KEY
);
```