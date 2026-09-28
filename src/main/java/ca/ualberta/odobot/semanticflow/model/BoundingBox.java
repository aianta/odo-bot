package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonObject;

public class BoundingBox {
    private int x;
    private int y;
    private int width;
    private int height;
    private int top;
    private int bottom;
    private int left;
    private int right;

    public BoundingBox(JsonObject boundingBox){
        x = boundingBox.getFloat("x").intValue();
        y = boundingBox.getFloat("y").intValue();
        width = boundingBox.getFloat("width").intValue();
        height = boundingBox.getFloat("height").intValue();
        top = boundingBox.getFloat("top").intValue();
        bottom = boundingBox.getFloat("bottom").intValue();
        left = boundingBox.getFloat("left").intValue();
        right = boundingBox.getFloat("right").intValue();
    }

    public JsonObject toJson(){
        JsonObject json = new JsonObject();
        json.put("x", x);
        json.put("y", y);
        json.put("width", width);
        json.put("height", height);
        json.put("top", top);
        json.put("bottom", bottom);
        json.put("left", left);
        json.put("right", right);
        return json;
    }

    public int getX() {
        return x;
    }

    public BoundingBox setX(int x) {
        this.x = x;
        return this;
    }

    public int getY() {
        return y;
    }

    public BoundingBox setY(int y) {
        this.y = y;
        return this;
    }

    public int getWidth() {
        return width;
    }

    public BoundingBox setWidth(int width) {
        this.width = width;
        return this;
    }

    public int getHeight() {
        return height;
    }

    public BoundingBox setHeight(int height) {
        this.height = height;
        return this;
    }

    public int getTop() {
        return top;
    }

    public BoundingBox setTop(int top) {
        this.top = top;
        return this;
    }

    public int getBottom() {
        return bottom;
    }

    public BoundingBox setBottom(int bottom) {
        this.bottom = bottom;
        return this;
    }

    public int getLeft() {
        return left;
    }

    public BoundingBox setLeft(int left) {
        this.left = left;
        return this;
    }

    public int getRight() {
        return right;
    }

    public BoundingBox setRight(int right) {
        this.right = right;
        return this;
    }
}
