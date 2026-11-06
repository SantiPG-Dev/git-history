package com.githistory.ui;

import com.githistory.git.CommitInfo;
import com.githistory.git.GraphRow;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * Celda del historial: dibuja el tramo del grafo correspondiente a la fila
 * (carril del nodo, verticales que lo atraviesan y diagonales de merge)
 * seguido de las etiquetas de refs y los datos del commit.
 */
public class HistoryCell extends ListCell<GraphRow> {

    public static final double LANE_WIDTH = 16;
    public static final double ROW_HEIGHT = 28;

    private static final Color[] PALETTE = {
            Color.web("#89b4fa"), Color.web("#f38ba8"), Color.web("#a6e3a1"),
            Color.web("#f9e2af"), Color.web("#cba6f7"), Color.web("#f5c2e7"),
            Color.web("#94e2d5"), Color.web("#fab387")
    };
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public interface CommitActions {
        void createBranchAt(CommitInfo commit);
        void createTagAt(CommitInfo commit);
        void checkoutCommit(CommitInfo commit);
        void cherryPick(CommitInfo commit);
        void revert(CommitInfo commit);
        void resetToHere(CommitInfo commit);
        void squashWithParent(CommitInfo commit);
        void copyHash(CommitInfo commit);
    }

    private final IntSupplier maxLanes;
    private final CommitActions actions;
    private final Function<String, Image> avatarFor;
    private final Canvas canvas = new Canvas();
    private final HBox refsBox = new HBox(4);
    private final Label message = new Label();
    private final Label author = new Label();
    private final Label date = new Label();
    private final Label hash = new Label();
    private final HBox box;

    public HistoryCell(IntSupplier maxLanes, Function<String, Image> avatarFor,
                       CommitActions actions) {
        this.maxLanes = maxLanes;
        this.actions = actions;
        this.avatarFor = avatarFor;
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
        MenuItem tag = new MenuItem("Crear etiqueta aquí…");
        tag.setOnAction(e -> actions.createTagAt(row.commit()));
        MenuItem checkout = new MenuItem("Checkout (HEAD separado)");
        checkout.setOnAction(e -> actions.checkoutCommit(row.commit()));
        MenuItem cherryPick = new MenuItem("Cherry-pick en la rama actual");
        cherryPick.setOnAction(e -> actions.cherryPick(row.commit()));
        MenuItem revert = new MenuItem("Revertir este commit");
        revert.setOnAction(e -> actions.revert(row.commit()));
        MenuItem reset = new MenuItem("Reset rama actual a este commit…");
        reset.setOnAction(e -> actions.resetToHere(row.commit()));
        MenuItem squash = new MenuItem("Fusionar con el padre (squash)");
        squash.setOnAction(e -> actions.squashWithParent(row.commit()));
        MenuItem copy = new MenuItem("Copiar hash");
        copy.setOnAction(e -> actions.copyHash(row.commit()));
        setContextMenu(new ContextMenu(branch, tag, checkout,
                new SeparatorMenuItem(), cherryPick, revert, squash, reset,
                new SeparatorMenuItem(), copy));

        setGraphic(box);
    }

    private void draw(GraphRow row) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.setLineWidth(2);
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
            curve(g, d, mid);
        }

        double x = laneX(row.column());
        Color color = color(row.column());
        boolean isHead = row.commit().refs().stream().anyMatch(CommitInfo.RefLabel::current);

        // Avatar del autor dentro del nodo (si está en caché); si no, punto.
        Image avatar = row.commit().email() == null ? null : avatarFor.apply(row.commit().email());
        if (avatar != null) {
            g.save();
            g.beginPath();
            g.arc(x, mid, 8, 8, 0, 360);
            g.closePath();
            g.clip();
            g.drawImage(avatar, x - 8, mid - 8, 16, 16);
            g.restore();
            g.setStroke(color);
            g.strokeOval(x - 8, mid - 8, 16, 16);
        } else {
            g.setFill(Color.web("#1e1e2e"));
            g.fillOval(x - 5.5, mid - 5.5, 11, 11);
            g.setStroke(color);
            g.strokeOval(x - 5.5, mid - 5.5, 11, 11);
            g.setFill(color);
            g.fillOval(x - 2, mid - 2, 4, 4);
        }
        if (isHead) {
            g.setStroke(Color.web("#cdd6f4"));
            g.setLineDashes(3);
            g.strokeOval(x - 10.5, mid - 10.5, 21, 21);
            g.setLineDashes((double[]) null);
        }
    }

    private void line(GraphicsContext g, int lane, double x1, double y1, double x2, double y2) {
        g.setStroke(color(lane));
        g.strokeLine(x1, y1, x2, y2);
    }

    /**
     * Curva suave entre carriles:
     * - fromTop: un carril que muere converge en el nodo de esta fila.
     * - toBottom: del nodo nace un carril nuevo hacia el padre (merge/rama).
     * - ninguna: línea horizontal a un carril ya existente en la misma fila.
     */
    private void curve(GraphicsContext g, GraphRow.Diagonal d, double mid) {
        g.setStroke(color(d.toBottom() ? d.toLane() : d.fromLane()));
        double fromX = laneX(d.fromLane());
        double toX = laneX(d.toLane());
        g.beginPath();
        if (d.fromTop()) {
            g.moveTo(fromX, 0);
            g.quadraticCurveTo(fromX, mid, toX, mid);
        } else if (d.toBottom()) {
            g.moveTo(fromX, mid);
            g.quadraticCurveTo(toX, mid, toX, ROW_HEIGHT);
        } else {
            g.moveTo(fromX, mid);
            g.lineTo(toX, mid);
        }
        g.stroke();
    }

    private static double laneX(int lane) {
        return 5 + LANE_WIDTH / 2 + lane * LANE_WIDTH;
    }

    private static Color color(int lane) {
        return PALETTE[Math.floorMod(lane, PALETTE.length)];
    }
}
