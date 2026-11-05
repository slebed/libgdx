package io.github.slebed.gdxvulkan.demos;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;

/**
 * The demos' keys, with a touch equivalent on a phone: a tap on the left half of the screen is Space, a tap on the right half is L, and the Back button is Esc.
 * On a desktop it is {@code isKeyJustPressed}, nothing else.
 */
final class Controls {
    private Controls () {}

    static boolean touch () {
        return Gdx.app != null && Gdx.app.getType() == Application.ApplicationType.Android;
    }

    static boolean justPressed (int key) {
        if (Gdx.input.isKeyJustPressed(key)) return true;
        if (!touch()) return false;
        if (key == Input.Keys.ESCAPE) {
            if (!Backend.back && !Gdx.input.isKeyJustPressed(Input.Keys.BACK)) return false;
            Backend.back = false;
            return true;
        }
        if (!Gdx.input.justTouched()) return false;
        final boolean right = Gdx.input.getX() >= Gdx.graphics.getWidth() / 2;
        return key == Input.Keys.SPACE ? !right : key == Input.Keys.L && right;
    }
}
