package games.strategy.engine.framework.headlessGameServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import games.strategy.debug.ClientLogger;
import games.strategy.engine.ClientFileSystemHelper;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameParser;
import games.strategy.engine.framework.ui.NewGameChooserModel;
import games.strategy.triplea.Constants;
import games.strategy.util.UrlStreams;

/**
 * A list of all available games. We make sure we can parse them all, but we don't keep them in memory.
 */
public class AvailableGames {
  private static final boolean s_delayedParsing = false;
  private static final String ZIP_EXTENSION = ".zip";
  private final TreeMap<String, URI> m_availableGames = new TreeMap<>();
  private final Set<String> m_availableMapFolderOrZipNames = new HashSet<>();

  public AvailableGames() {
    final Set<String> mapNamePropertyList = new HashSet<>();
    populateAvailableGames(m_availableGames, m_availableMapFolderOrZipNames, mapNamePropertyList);
    // System.out.println(mapNamePropertyList);
    // System.out.println(m_availableMapFolderOrZipNames);
    m_availableMapFolderOrZipNames.retainAll(mapNamePropertyList);
    // System.out.println(m_availableMapFolderOrZipNames);
  }

  public List<String> getGameNames() {
    return new ArrayList<>(m_availableGames.keySet());
  }

  public Set<String> getAvailableMapFolderOrZipNames() {
    return new HashSet<>(m_availableMapFolderOrZipNames);
  }

  /**
   * Can return null.
   */
  public GameData getGameData(final String gameName) {
    return getGameDataFromXML(m_availableGames.get(gameName));
  }

  public URI getGameURI(final String gameName) {
    return m_availableGames.get(gameName);
  }

  public String getGameFilePath(final String gameName) {
    return getGameXMLLocation(m_availableGames.get(gameName));
  }

  private static void populateAvailableGames(final Map<String, URI> availableGames,
      final Set<String> availableMapFolderOrZipNames, final Set<String> mapNamePropertyList) {
    System.out.println("Parsing all available games (this could take a while). ");
    for (final File map : allMapFiles()) {
      if (map.isDirectory()) {
        populateFromDirectory(map, availableGames, availableMapFolderOrZipNames, mapNamePropertyList);
      } else if (map.isFile() && map.getName().toLowerCase().endsWith(ZIP_EXTENSION)) {
        populateFromZip(map, availableGames, availableMapFolderOrZipNames, mapNamePropertyList);
      }
    }
    System.out.println("Finished parsing all available game xmls. ");
  }

  private static List<File> allMapFiles() {
    final List<File> rVal = new ArrayList<>();
    // prioritize user maps folder over root folder
    rVal.addAll(safeListFiles(ClientFileSystemHelper.getUserMapsFolder()));
    rVal.addAll(safeListFiles(NewGameChooserModel.getDefaultMapsDir()));
    return rVal;
  }

  private static List<File> safeListFiles(final File f) {
    final File[] files = f.listFiles();
    if (files == null) {
      return Collections.emptyList();
    }
    return Arrays.asList(files);
  }

  private static void populateFromDirectory(final File mapDir, final Map<String, URI> availableGames,
      final Set<String> availableMapFolderOrZipNames, final Set<String> mapNamePropertyList) {
    final File games = new File(mapDir, "games");
    if (!games.exists()) {
      // no games in this map dir
      return;
    }
    for (final File game : games.listFiles()) {
      if (game.isFile() && game.getName().toLowerCase().endsWith("xml")) {
        final boolean added = addToAvailableGames(game.toURI(), availableGames, mapNamePropertyList);
        if (added) {
          availableMapFolderOrZipNames.add(mapDir.getName());
        }
      }
    }
  }

  private static void populateFromZip(final File map, final Map<String, URI> availableGames,
      final Set<String> availableMapFolderOrZipNames, final Set<String> mapNamePropertyList) {
    try (
        final FileInputStream fis = new FileInputStream(map);
        final ZipInputStream zis = new ZipInputStream(fis);
        final URLClassLoader loader = new URLClassLoader(new URL[] {map.toURI().toURL()});) {
      ZipEntry entry = zis.getNextEntry();
      while (entry != null) {
        if (entry.getName().startsWith("games/") && entry.getName().toLowerCase().endsWith(".xml")) {
          final URL url = loader.getResource(entry.getName());
          // we have to close the loader to allow files to be deleted on windows
          try {
            final boolean added = addToAvailableGames(new URI(url.toString().replace(" ", "%20")), availableGames,
                mapNamePropertyList);
            if (added && map.getName().length() > 4) {
              availableMapFolderOrZipNames
                  .add(map.getName().substring(0, map.getName().length() - ZIP_EXTENSION.length()));
            }
          } catch (final URISyntaxException e) {
            // only happens when URI couldn't be build and therefore no entry was added. That's fine
          }
        }
        zis.closeEntry();
        entry = zis.getNextEntry();
      }
    } catch (final IOException e) {
      ClientLogger.logQuietly("Map: " + map, e);
    }
  }

  private static boolean addToAvailableGames(final URI uri, final Map<String, URI> availableGames,
      final Set<String> mapNamePropertyList) {
    if (uri == null) {
      return false;
    }
    final AtomicReference<String> gameName = new AtomicReference<>();

    final Optional<InputStream> inputStream = UrlStreams.openStream(uri);
    if (inputStream.isPresent()) {
      try (InputStream input = inputStream.get()) {
        final GameData data = new GameParser(uri.toString()).parse(input, gameName, s_delayedParsing);
        final String name = data.getGameName();
        final String mapName = data.getProperties().get(Constants.MAP_NAME, "");
        if (!availableGames.containsKey(name)) {
          availableGames.put(name, uri);
          if (mapName.length() > 0) {
            mapNamePropertyList.add(mapName);
          }
          return true;
        }
      } catch (final Exception e) {
        ClientLogger.logError("Exception while parsing: " + uri.toString() + " : "
            + (gameName.get() != null ? gameName.get() + " : " : ""), e);
      }
    }
    return false;
  }

  private static String getGameXMLLocation(final URI uri) {
    if (uri == null) {
      return null;
    }
    final String raw = uri.toString();
    final String base = ClientFileSystemHelper.getRootFolder().toURI().toString() + "maps";
    if (raw.startsWith(base)) {
      return raw.substring(base.length());
    }
    if (raw.startsWith("jar:" + base)) {
      return raw.substring("jar:".length() + base.length());
    }
    return raw;
  }

  private static GameData getGameDataFromXML(final URI uri) {
    if (uri == null) {
      return null;
    }
    final AtomicReference<String> gameName = new AtomicReference<>();

    final Optional<InputStream> inputStream = UrlStreams.openStream(uri);
    if (inputStream.isPresent()) {
      try (InputStream input = inputStream.get()) {
        return new GameParser(uri.toString()).parse(input, gameName, false);
      } catch (final Exception e) {
        ClientLogger.logError("Exception while parsing: " + uri.toString() + " : "
            + (gameName.get() != null ? gameName.get() + " : " : ""), e);
      }
    }
    return null;
  }
}
