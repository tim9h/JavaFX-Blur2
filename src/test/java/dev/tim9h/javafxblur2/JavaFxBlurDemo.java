package dev.tim9h.javafxblur2;

import dev.tim9h.javafxblur2.WindowsBackdrop.Effect;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class JavaFxBlurDemo extends Application {

	private Effect effect = Effect.ACRYLIC;

	private boolean dark = true;

	private double dragX;

	private double dragY;

	@Override
	public void start(Stage primaryStage) {
		var title = new Label("WindowsBackdrop demo");
		title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #202020;");

		var status = new Label();

		var toggle = new Button("Toggle effect");
		toggle.setPrefWidth(160);
		toggle.setOnAction(_ -> {
			effect = (effect == Effect.ACRYLIC) ? Effect.MICA : Effect.ACRYLIC;
			applyBackdrop(primaryStage, status, title);
		});

		var theme = new Button("Toggle dark/light");
		theme.setPrefWidth(160);
		theme.setOnAction(_ -> {
			dark = !dark;
			applyBackdrop(primaryStage, status, title);
		});

		var close = new Button("Close");
		close.setPrefWidth(160);
		close.setOnAction(_ -> primaryStage.close());

		var root = new VBox(12, title, status, toggle, theme, close);
		root.setAlignment(Pos.CENTER);
		root.setPadding(new Insets(30));
		// Keep the JavaFX background transparent; the dark look comes from the acrylic
		// tint, not an
		// opaque fill. A faint overlay just keeps the controls legible.
		root.setStyle("-fx-background-color: rgba(100,255,255,0.01);");
		enableDrag(primaryStage, root);

		var scene = new Scene(root, 420, 320);
		scene.setFill(Color.TRANSPARENT);

		primaryStage.initStyle(StageStyle.TRANSPARENT);
		primaryStage.setTitle("WindowsBackdrop demo");
		primaryStage.setScene(scene);
		primaryStage.show();

		// Must be after show(): the native window handle only exists once the stage is
		// visible.
		applyBackdrop(primaryStage, status, title);

		// Round the window (and the acrylic) corners; re-apply on resize.
		WindowsBackdrop.roundCorners(primaryStage, 16);
		primaryStage.widthProperty().addListener((_, _, _) -> WindowsBackdrop.roundCorners(primaryStage, 16));
		primaryStage.heightProperty().addListener((_, _, _) -> WindowsBackdrop.roundCorners(primaryStage, 16));
	}

	private void applyBackdrop(Stage stage, Label status, Label title) {
		int tint = dark ? WindowsBackdrop.DARK_TINT : WindowsBackdrop.LIGHT_TINT;
		var textColor = dark ? "#f0f0f0" : "#202020";
		title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: " + textColor + ";");

		boolean ok = WindowsBackdrop.apply(stage, effect, tint);
		if (ok) {
			status.setText("Applied: " + effect + " / " + (dark ? "dark" : "light"));
			status.setStyle("-fx-text-fill: " + textColor + ";");
			stage.getScene().setFill(Color.TRANSPARENT);
		} else {
			System.err.println("Failed to apply " + effect + " effect");
			String hint = (effect == Effect.MICA)
					? "MICA needs Windows 11 and a non-transparent window; use ACRYLIC for this transparent stage"
					: effect + " not available on this platform";
			status.setText(hint);
			status.setStyle("-fx-text-fill: #d08a2a;");
			// Keep the working effect visible instead of a blank window: fall back to
			// acrylic.
			if (effect == Effect.MICA && WindowsBackdrop.apply(stage, Effect.ACRYLIC, tint)) {
				stage.getScene().setFill(Color.TRANSPARENT);
			} else {
				stage.getScene().setFill(dark ? Color.web("#202020") : Color.web("#f0f0f0"));
			}
		}
	}

	private void enableDrag(Stage stage, VBox handle) {
		handle.setOnMousePressed(e -> {
			dragX = e.getScreenX() - stage.getX();
			dragY = e.getScreenY() - stage.getY();
		});
		handle.setOnMouseDragged(e -> {
			stage.setX(e.getScreenX() - dragX);
			stage.setY(e.getScreenY() - dragY);
		});
	}

	public static void main(String[] args) {
		Application.launch(args);
	}
}