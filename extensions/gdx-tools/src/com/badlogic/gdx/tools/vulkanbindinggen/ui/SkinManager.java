package com.badlogic.gdx.tools.vulkanbindinggen.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;

public class SkinManager {

    public static Skin createBasicSkin() {
        Skin skin = new Skin();

        // Generate a BitmapFont (requires gdx-freetype extension)
        // Alternatively, load a default font: new BitmapFont()
        BitmapFont font = new BitmapFont(); // Use default libGDX font for simplicity
        skin.add("default-font", font, BitmapFont.class);

        // Create basic drawables (white pixel)
        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(Color.WHITE);
        pixmap.fill();
        skin.add("white", new Texture(pixmap));
        pixmap.dispose();

        // Create simple NinePatch drawables for backgrounds (optional, can use solid colors)
        // TextureRegion whiteRegion = skin.getRegion("white");
        // skin.add("background", new NinePatch(whiteRegion, 4, 4, 4, 4)); // Example ninepatch

        // Label Style
        Label.LabelStyle labelStyle = new Label.LabelStyle();
        labelStyle.font = skin.getFont("default-font");
        labelStyle.fontColor = Color.WHITE;
        skin.add("default", labelStyle);

        // TextButton Style
        TextButton.TextButtonStyle textButtonStyle = new TextButton.TextButtonStyle();
        textButtonStyle.font = skin.getFont("default-font");
        textButtonStyle.fontColor = Color.WHITE;
        textButtonStyle.up = skin.newDrawable("white", Color.DARK_GRAY);
        textButtonStyle.down = skin.newDrawable("white", Color.LIGHT_GRAY);
        textButtonStyle.over = skin.newDrawable("white", Color.GRAY);
        textButtonStyle.disabledFontColor = Color.GRAY;
        skin.add("default", textButtonStyle);

        // CheckBox Style
        CheckBox.CheckBoxStyle checkBoxStyle = new CheckBox.CheckBoxStyle();
        checkBoxStyle.font = skin.getFont("default-font");
        checkBoxStyle.fontColor = Color.WHITE;
        checkBoxStyle.checkboxOn = skin.newDrawable("white", Color.GREEN); // Simple green square
        checkBoxStyle.checkboxOff = skin.newDrawable("white", Color.LIGHT_GRAY); // Simple gray square
        checkBoxStyle.checkboxOver = skin.newDrawable("white", Color.YELLOW);
        Drawable checkboxOnDisabled = skin.newDrawable("white", new Color(0, 0.5f, 0, 1f)); // Darker green
        Drawable checkboxOffDisabled = skin.newDrawable("white", Color.DARK_GRAY); // Darker gray
        checkBoxStyle.checkboxOnDisabled = checkboxOnDisabled;
        checkBoxStyle.checkboxOffDisabled = checkboxOffDisabled;
        checkBoxStyle.disabledFontColor = Color.GRAY;

        checkBoxStyle.checkboxOn.setMinWidth(16f);
        checkBoxStyle.checkboxOn.setMinHeight(16f);
        checkBoxStyle.checkboxOff.setMinWidth(16f);
        checkBoxStyle.checkboxOff.setMinHeight(16f);
        checkBoxStyle.checkboxOver.setMinWidth(16f);
        checkBoxStyle.checkboxOver.setMinHeight(16f);
        checkBoxStyle.checkboxOnDisabled.setMinWidth(16f);
        checkBoxStyle.checkboxOnDisabled.setMinHeight(16f);
        checkBoxStyle.checkboxOffDisabled.setMinWidth(16f);
        checkBoxStyle.checkboxOffDisabled.setMinHeight(16f);

        skin.add("default", checkBoxStyle);


        // ScrollPane Style
        ScrollPane.ScrollPaneStyle scrollPaneStyle = new ScrollPane.ScrollPaneStyle();
        scrollPaneStyle.vScroll = skin.newDrawable("white", Color.GRAY);
        scrollPaneStyle.vScrollKnob = skin.newDrawable("white", Color.LIGHT_GRAY);
        scrollPaneStyle.hScroll = skin.newDrawable("white", Color.GRAY);
        scrollPaneStyle.hScrollKnob = skin.newDrawable("white", Color.LIGHT_GRAY);
        skin.add("default", scrollPaneStyle);


        Button.ButtonStyle buttonStyle = new Button.ButtonStyle();
        buttonStyle.up = skin.newDrawable("white", Color.DARK_GRAY);   // Same as TextButton up
        buttonStyle.down = skin.newDrawable("white", Color.LIGHT_GRAY); // Same as TextButton down
        buttonStyle.over = skin.newDrawable("white", Color.GRAY);   // Same as TextButton over
        // Add disabled state if needed later:
        // buttonStyle.disabled = skin.newDrawable("white", Color.DARK_GRAY);
        skin.add("default", buttonStyle); // Register this style for Button class with name "default"


        return skin;
    }
}