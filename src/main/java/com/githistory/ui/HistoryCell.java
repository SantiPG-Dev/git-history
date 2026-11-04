package com.githistory.ui;

import com.githistory.git.CommitInfo;
import com.githistory.git.GraphRow;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.IntSupplier;

/**
 * Celda del historial: dibuja el tramo del grafo correspondiente a la fila
 * (carril del nodo, verticales que lo atraviesan y diagonales de merge)
 * seguido de las etiquetas de refs y los datos del commit.
 */
public class HistoryCell extends ListCell<GraphRow> {

    public static final double LANE_WIDTH = 14;
    public static final double ROW_HEIGHT = 26;

    private static final Color[] PALETTE = {
            Color.web("#4cc2ff"), Color.web("#ff7b72"), Color.web("#7ee787"),
            Color.web("#e3b341"), Color.web("#d2a8ff"), Color.web("#ff9bce"),
            Color.web("#76e3ea"), Color.web("#ffa657")
    };
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public interface CommitActions {
        void createBranchAt(CommitInfo commit);
        void checkoutCommit(CommitInfo commit);
        void copyHash(CommitInfo commit);
    }

    private final IntSupplier maxLanes;
    private final CommitActions actions;
    private final Canvas canvas = new Canvas();
    private final HBox refsBox = new HBox(4);
    private final Label message = new Label();
    private final Label author = new Label();
    private final Label date = new Label();
    private final Label hash = new Label();
    private final HBox box;

    public HistoryCell(IntSupplier maxLanes, CommitActions actions) {
        this.maxLanes = maxLanes;
        this.actions = actions;
        message.getStyleClass().add("graph-message");
        author.getStyleClass().add("graph-meta");
        date.getStyleClass().add("graph-meta");
        hash.getStyleClass().add("graph-hash");
        refsBox.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        box = new HBox(8, canvas, refsBox, message, spacer, author, date, hash);
        box.setAlignment(Pos.CENTER_LEFT);
    }

    @Override
    protected void updateItem(GraphRow row, boolean empty) {
        super.updateItem(row, empty);
        if (empty || row == null) {
            setGraphic(null);
            setContextMenu(null);
            return;
        }
        canvas.setWidth(Math.max(1, maxLanes.getAsInt()) * LANE_WIDTH + 10);
        canvas.setHeight(ROW_HEIGHT);
        draw(row);

        refsBox.getChildren().clear();
        for (CommitInfo.RefLabel ref : row.commit().refs()) {
            Label chip = new Label(ref.name());
            chip.getStyleClass().add("ref-chip");
            chip.getStyleClass().add(switch (ref.type()) {
                case LOCAL -> "ref-local";
                case REMOTE -> "ref-remote";
                case TAG -> "ref-tag";
            });
            if (ref.current()) {
                chip.getStyleClass().add("ref-head");
            }
            refsBox.getChildren().add(chip);
        }
        message.setText(row.commit().message());
        author.setText(row.commit().author());
        date.setText(TIME_FORMAT.format(row.commit().time()));
        hash.setText(row.commit().shortId());

        MenuItem branch = new MenuItem("Crear rama aquí…");
        branch.setOnAction(e -> actions.createBranchAt(row.commit()));
        MenuItem checkout = new MenuItem("Checkout (HEAD separado)");
        checkout.setOnAction(e -> actions.checkoutCommit(row.commit()));
        MenuItem copy = new MenuItem("Copiar hash");
        copy.setOnAction(e -> actions.copyHash(row.commit()));
        setContextMenu(new ContextMenu(branch, checkout, copy));

        setGraphic(box);
    }

    private void draw(GraphRow row) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.setLineWidth(1.6);
        double mid = ROW_HEIGHT / 2;
        for (int lane : row.fullVerticals()) {
            line(g, lane, laneX(lane), 0, laneX(lane), ROW_HEIGHT);
        }
        for (int lane : row.topVerticals()) {
            line(g, lane, laneX(lane), 0, laneX(lane), mid);
        }
        for (int lane : row.bottomVerticals()) {
            line(g, lane, laneX(lane), mid, laneX(lane), ROW_HEIGHT);
        }
        for (GraphRow.Diagonal d : row.diagonals()) {
            line(g, d.toLane(), laneX(d.fromLane()), mid, laneX(d.toLane()),
                    d.toBottom() ? ROW_HEIGHT : mid);
        }
        double x = laneX(row.column());
        Color color = color(row.column());
        g.setFill(Color.web("#1e1e2e"));
        g.fillOval(x - 4.5, mid - 4.5, 9, 9);
        g.setStroke(color);
        g.strokeOval(x - 4.5, mid - 4.5, 9, 9);
        g.setFill(color);
        g.fillOval(x - 2, mid - 2, 4, 4);
    }

    private void line(GraphicsContext g, int lane, double x1, double y1, double x2, double y2) {
        g.setStroke(color(lane));
        g.strokeLine(x1, y1, x2, y2);
    }

    private static double laneX(int lane) {
        return 5 + LANE_WIDTH / 2 + lane * LANE_WIDTH;
    }

    private static Color color(int lane) {
        return PALETTE[Math.floorMod(lane, PALETTE.length)];
    }
}
