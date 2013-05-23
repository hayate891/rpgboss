package rpgboss.player
import rpgboss.player.entity._
import rpgboss.model._
import rpgboss.model.Constants._
import rpgboss.model.resource._
import com.badlogic.gdx.audio.{ Music => GdxMusic }
import com.badlogic.gdx.graphics._
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.Gdx
import java.util.concurrent.FutureTask
import java.util.concurrent.Callable
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import rpgboss.player.entity.PlayerEntity
import rpgboss.player.entity.NonplayerEntity
import rpgboss.player.entity.Entity
import aurelienribon.tweenengine._

/**
 * This class contains all the state information about the game.
 *
 * It must ensure that different threads can mutate the state of the game
 * without causing concurrency errors.
 *
 * Moreover, OpenGL operations may only occur on the GDX rendering thread.
 * This object must post those operations to that thread via postRunnable
 */
class GameState(game: MyGame, project: Project) {
  val tweenManager = new TweenManager()

  // No need for syncronization, since it's a synchronized collection
  val windows = new collection.mutable.ArrayBuffer[Window] with collection.mutable.SynchronizedBuffer[Window]

  val musics = Array.fill[Option[GdxMusic]](8)(None)

  // Should only be modified on the Gdx thread
  var curTransition: Option[Transition] = None

  // current map
  var mapAndAssetsOption: Option[MapAndAssets] = None

  // protagonist. Modify all these things on the Gdx thread
  var playerEvt: PlayerEntity = new PlayerEntity(game)
  setPlayerSprite(game.project.data.characters.head.sprite)

  val persistent = new PersistentState()

  // All the events on the current map, including the player event
  var npcEvts = List[NonplayerEntity]()

  // Called every frame... by MyGame's render call. 
  def update(delta: Float) = {
    // Update tweens
    tweenManager.update(delta)

    // Update events, including player event
    npcEvts.foreach(_.update(delta))
    playerEvt.update(delta)

    // Update windows
    if (!windows.isEmpty)
      windows.head.update(delta, true)
    if (windows.length > 1)
      windows.tail.foreach(_.update(delta, false))

    // Update current transition
    curTransition.synchronized {
      curTransition map { transition =>
        if (transition.done) {
          curTransition = None
        }
      }
    }

    // Update camera location
    if (playerEvt.isMoving) {
      persistent.cameraLoc.x = playerEvt.x
      persistent.cameraLoc.y = playerEvt.y
    }
  }

  /**
   * Run the following on the GUI thread
   */
  def syncRun(op: => Any) = {
    val runnable = new Runnable() {
      def run() = op
    }
    Gdx.app.postRunnable(runnable)
  }

  /**
   * Calls the following on the GUI thread. Takes at least a frame.
   */
  def syncCall[T](op: => T): T = {
    val callable = new Callable[T]() {
      def call() = op
    }
    val future = new FutureTask(callable)

    Gdx.app.postRunnable(future)

    future.get
  }

  /**
   * Dispose of any disposable resources
   */
  def dispose() = {
    mapAndAssetsOption.map(_.dispose())
  }

  /*
   * The below functions are all called from the script threads only.
   */

  /*
   * Things to do with the player's location and camera
   */

  def setPlayerSprite(spritespec: Option[SpriteSpec]) = syncRun {
    playerEvt.setSprite(spritespec)
  }

  def setPlayerLoc(loc: MapLoc) = syncRun {
    playerEvt.x = loc.x
    playerEvt.y = loc.y
  }

  def setCameraLoc(loc: MapLoc) = syncRun {
    persistent.cameraLoc.set(loc)

    // Update internal resources for the map
    if (persistent.cameraLoc.map.isEmpty()) {
      mapAndAssetsOption.map(_.dispose())
      mapAndAssetsOption = None
      npcEvts = List()
    } else {
      mapAndAssetsOption.map(_.dispose())

      val mapAndAssets = new MapAndAssets(project, loc.map)
      mapAndAssetsOption = Some(mapAndAssets)
      npcEvts = mapAndAssets.mapData.events.map {
        new NonplayerEntity(game, _)
      }.toList
    }
  }

  /* 
   * Things to do with the screen
   */

  def setTransition(
    startAlpha: Float,
    endAlpha: Float,
    durationMs: Int) = syncRun {
    curTransition = Some(Transition(startAlpha, endAlpha, durationMs))
  }

  def showPicture(slot: Int, name: String, x: Int, y: Int, w: Int, h: Int) =
    syncRun {
      persistent.pictures(slot).map(_.dispose())
      persistent.pictures(slot) = Some(PictureInfo(project, name, x, y, w, h))
    }

  def hidePicture(slot: Int) = syncRun {
    persistent.pictures(slot).map(_.dispose())
    persistent.pictures(slot) = None
  }

  def playMusic(slot: Int, specOpt: Option[SoundSpec],
                loop: Boolean, fadeDurationMs: Int) = syncRun {
    musics(slot).map(_.dispose())

    musics(slot) = specOpt.map { spec =>
      val resource = Music.readFromDisk(project, spec.sound)
      resource.loadAsset(game.assets)
      // TODO: fix this blocking call
      game.assets.finishLoading()
      val newMusic = resource.getAsset(game.assets)

      // Start at zero volume and fade to desired volume
      newMusic.setVolume(0f)
      newMusic.setLooping(loop)
      newMusic.play()

      // Setup volume tween
      val tweenableMusic = new GdxMusicTweenable(newMusic)
      Tween.to(tweenableMusic, GdxMusicAccessor.VOLUME, fadeDurationMs/1000f)
        .target(spec.volume).start(tweenManager)

      newMusic
    }
  }

  def stopMusic(slot: Int, fadeDurationMs: Int) = syncRun {
    musics(slot).map(_.dispose())
    musics(slot) = None
  }

  /*
   * Things to do with user interaction
   */
  def sleep(durationMs: Int) = {
    Thread.sleep(durationMs)
  }

  def showChoices(
    choices: Array[String],
    x: Int, y: Int, w: Int, h: Int,
    justification: Int): Int = {
    val window = new ChoiceWindow(
      game.assets,
      project,
      choices,
      x, y, w, h,
      game.screenLayer.windowskin,
      game.screenLayer.windowskinRegion,
      game.screenLayer.fontbmp,
      initialState = Window.Opening,
      msPerChar = 0,
      justification = justification)

    windows.prepend(window)
    game.inputs.prepend(window)

    // Return the choice... eventually...
    val choice = Await.result(window.result.future, Duration.Inf)

    game.inputs.remove(window)
    windows -= window

    choice
  }

  def showTextWithPosition(
    text: Array[String],
    x: Int = 0, y: Int = 320, w: Int = 640, h: Int = 160) = {
    val window = new Window(
      game.assets,
      project,
      text,
      x, y, w, h,
      game.screenLayer.windowskin,
      game.screenLayer.windowskinRegion,
      game.screenLayer.fontbmp,
      initialState = Window.Opening)

    windows.prepend(window)
    game.inputs.prepend(window)

    Await.result(window.result.future, Duration.Inf)

    game.inputs.remove(window)
    windows -= window
  }

  def showText(text: Array[String]) = showTextWithPosition(text)

  def getEvtState(evtName: String): Int =
    getEvtState(persistent.cameraLoc.map, evtName)
  def getEvtState(mapName: String, evtName: String) =
    persistent.getEventState(mapName, evtName)
  def setEvtState(evtName: String, newState: Int): Unit =
    setEvtState(persistent.cameraLoc.map, evtName, newState)
  def setEvtState(mapName: String, evtName: String, newState: Int) = {
    persistent.setEventState(mapName, evtName, newState)

    if (mapName == persistent.cameraLoc.map) {
      npcEvts.filter(_.mapEvent.name == evtName).foreach(_.updateState())
    }
  }

  val LEFT = Window.Left
  val CENTER = Window.Center
  val RIGHT = Window.Right
}

/**
 * Need call on dispose first
 */
case class PictureInfo(
  project: Project,
  name: String,
  x: Int, y: Int, w: Int, h: Int) {
  val picture = Picture.readFromDisk(project, name)
  val texture =
    new Texture(Gdx.files.absolute(picture.dataFile.getAbsolutePath()))

  def dispose() = texture.dispose()

  def render(batch: SpriteBatch) = {
    batch.draw(texture,
      x, y, w, h,
      0, 0, texture.getWidth(), texture.getHeight(),
      false, true)
  }
}