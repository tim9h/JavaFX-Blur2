# JavaFX Blur 2

Native Windows backdrop effects for JavaFX using the Java 26 Foreign Function & Memory API (Project Panama).

This project adds Windows-specific glass and material blur effects to a JavaFX stage without requiring a custom DLL, JNI bridge, or native library loading.

## Features

- Native acrylic blur for Windows 10/11
- Native Mica backdrop support for Windows 11
- Rounded-corner window support
- No DLL or JNI required; uses Java Panama (`java.lang.foreign`)
- Integrates directly with a JavaFX `Stage`

## Requirements

- Windows 10 or Windows 11
- Java 26+
- JavaFX 28 EA (or a compatible JavaFX build matching your JDK)

Important:
- `WindowsBackdrop.apply(...)` must be called after the stage is shown.
- For the effect to be visible, the stage should usually use `StageStyle.TRANSPARENT` and a transparent JavaFX `Scene` background.
- Mica is available only on Windows 11.

## Maven

Add the dependency:

```xml
<dependency>
    <groupId>dev.tim9h</groupId>
    <artifactId>javafx-blur2</artifactId>
    <version>...</version>
</dependency>
```

If you are consuming it from GitHub Packages, make sure your Maven settings include the GitHub repository configuration used by this project.

## Example

```java
import dev.tim9h.javafxblur2.WindowsBackdrop;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public final class DemoApp extends Application {
    @Override
    public void start(Stage stage) {
        var root = new StackPane();
        root.setStyle("-fx-background-color: rgba(255,255,255,0.08);");

        var scene = new Scene(root, 900, 600, Color.TRANSPARENT);
        stage.setScene(scene);
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.show();

        WindowsBackdrop.apply(stage, WindowsBackdrop.Effect.ACRYLIC, WindowsBackdrop.DARK_TINT);
        WindowsBackdrop.roundCorners(stage, 18);
    }
}
```


## API summary

```java
WindowsBackdrop.apply(Stage stage)
WindowsBackdrop.apply(Stage stage, WindowsBackdrop.Effect effect)
WindowsBackdrop.apply(Stage stage, WindowsBackdrop.Effect effect, int tintArgb)
WindowsBackdrop.roundCorners(Stage stage, int radiusPx)
WindowsBackdrop.clearRoundedCorners(Stage stage)
```

The tint is given as an ARGB value, for example:

```java
WindowsBackdrop.LIGHT_TINT
WindowsBackdrop.DARK_TINT
```