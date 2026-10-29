package dev.gravaai.ui.kit;

import dev.gravaai.ui.AppFonts;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/**
 * P1.3 visual gate: every kit component in every state, next to the React reference
 * (plan §5.12, §6.3, §7.1 P1.3).
 *
 * <p>App entry points:
 * <ul>
 *   <li>{@link #show(Stage)} — build and show the showcase on the given stage
 *       ({@code gravaai --ui-kit} wiring; the app calls this instead of the dashboard).
 *   <li>{@link #createScene()} — the showcase scene, for TestFX/snapshot tests.
 * </ul>
 *
 * <p>Development harness: {@code dev.gravaai.ui.kit.UiKitShowcase.Main} (also the screenshot run).
 * Set {@code -Dgravaai.uiKit.demo=overlays} to auto-open toasts so a non-interactive screenshot
 * captures an overlay state too.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class UiKitShowcase {

	private static ScrollPane galleryScroll;

	private UiKitShowcase() {}

	/** Builds the showcase scene (kit stylesheet installed, DM Sans loaded). */
	public static Scene createScene() {
		AppFonts.load();
		KitRoot root = new KitRoot(gallery());
		Scene scene = new Scene(root, 1440, 1000);
		Theme.install(scene);
		scene.setFill(Theme.BG);
		return scene;
	}

	/** Shows the showcase on the given stage ({@code gravaai --ui-kit}). */
	public static void show(Stage stage) {
		stage.setTitle("Gravaai UI kit");
		stage.setScene(createScene());
		ShowcaseStage.place(stage);
		stage.show();
		runOverlayDemo(stage);
		String scrollTo = System.getProperty("gravaai.uiKit.scroll", "");
		if (!scrollTo.isEmpty() && galleryScroll != null) {
			// Wait for the first layout pulse so the scrollable height is known.
			javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(
					javafx.util.Duration.millis(600));
			pause.setOnFinished(event -> galleryScroll.setVvalue(
					scrollTo.equals("bottom") ? 1 : Double.parseDouble(scrollTo)));
			pause.play();
		}
	}

	private static void runOverlayDemo(Stage stage) {
		String demo = System.getProperty("gravaai.uiKit.demo", "");
		if (!demo.contains("overlays")) {
			return;
		}
		Platform.runLater(() -> {
			Toast.show(stage.getScene().getRoot(), "Recording saved")
					.description("recording-2026-10-27.mkv")
					.variant(Toast.Variant.SUCCESS);
			Toast.show(stage.getScene().getRoot(), "Could not copy the error")
					.variant(Toast.Variant.DANGER)
					.duration(javafx.util.Duration.INDEFINITE);
		});
	}

	private static Parent gallery() {
		VBox content = new VBox(20);
		content.setPadding(new Insets(24));
		content.getStyleClass().add("showcase-root");

		VBox header = new VBox(4);
		Label title = new Label("Gravaai UI kit");
		title.getStyleClass().add("text-2xl");
		title.setStyle("-fx-font-weight: 600;");
		Label subtitle = new Label(
				"P1.3 visual gate — dark theme, DM Sans, HeroUI measurements; hover/focus states are live.");
		subtitle.getStyleClass().add("text-muted");
		header.getChildren().addAll(title, subtitle);

		content.getChildren().addAll(header,
				typography(),
				tokens(),
				buttons(),
				toggles(),
				switches(),
				sliders(),
				fields(),
				cards(),
				separators(),
				skeletons(),
				selects(),
				tabsSection(),
				accordions(),
				choices(),
				colorPickers(),
				meters(),
				clamps(),
				overlays());

		ScrollPane scroll = new ScrollPane(content);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("custom-scrollbar");
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
		scroll.setPannable(true);
		galleryScroll = scroll;
		return scroll;
	}

	private static VBox section(String title, Node... rows) {
		VBox section = new VBox(12);
		section.getStyleClass().add("showcase-section");
		Label heading = new Label(title);
		heading.getStyleClass().add("showcase-section-title");
		section.getChildren().add(heading);
		section.getChildren().addAll(rows);
		return section;
	}

	private static HBox row(Node... children) {
		HBox row = new HBox(12);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getChildren().addAll(children);
		return row;
	}

	private static FlowPane wrap(double hgap, double vgap, Node... children) {
		FlowPane pane = new FlowPane(hgap, vgap, children);
		pane.setAlignment(Pos.CENTER_LEFT);
		return pane;
	}

	/** One state of a component: caption above the node. */
	private static VBox state(String caption, Node node) {
		Label label = new Label(caption);
		label.getStyleClass().add("showcase-state");
		VBox box = new VBox(4, label, node);
		box.setAlignment(Pos.TOP_LEFT);
		return box;
	}

	private static VBox typography() {
		HBox sizes = new HBox(16);
		sizes.setAlignment(Pos.BASELINE_LEFT);
		for (String[] entry : new String[][] {
				{"text-xs 12", "text-xs"}, {"text-sm 14", "text-sm"}, {"text-base 16", "text-base"},
				{"text-lg 18", "text-lg"}, {"text-xl 20", "text-xl"}, {"text-2xl 24", "text-2xl"}}) {
			Label label = new Label("Ag");
			label.getStyleClass().add(entry[1]);
			sizes.getChildren().add(state(entry[0], label));
		}
		HBox weights = new HBox(16);
		for (String[] entry : new String[][] {
				{"400 regular", "font-normal"}, {"500 medium", "font-medium"},
				{"600 semibold", "font-semibold"}, {"700 bold", "font-bold"}}) {
			Label label = new Label("DM Sans");
			label.getStyleClass().addAll("text-sm", entry[1]);
			weights.getChildren().add(state(entry[0], label));
		}
		Label italics = new Label("Italic 400 / 600 — the bundled italic faces");
		italics.getStyleClass().add("text-sm");
		italics.setStyle("-fx-font-style: italic;");
		return section("Typography — DM Sans static instances (400/500/600/700 + italics)",
				sizes, weights, state("italic", italics));
	}

	private static VBox tokens() {
		FlowPane swatches = new FlowPane(12, 12);
		Object[][] entries = {
				{"-g-bg", Theme.BG}, {"-g-surface", Theme.SURFACE},
				{"-g-surface-secondary", Theme.SURFACE_SECONDARY},
				{"-g-surface-tertiary", Theme.SURFACE_TERTIARY}, {"-g-default", Theme.DEFAULT},
				{"-g-segment", Theme.SEGMENT}, {"-g-field", Theme.FIELD}, {"-g-overlay", Theme.OVERLAY},
				{"-g-border", Theme.BORDER}, {"-g-separator", Theme.SEPARATOR},
				{"-g-accent", Theme.ACCENT}, {"-g-accent-hover", Theme.ACCENT_HOVER},
				{"-g-muted", Theme.MUTED}, {"-g-success", Theme.SUCCESS},
				{"-g-warning", Theme.WARNING}, {"-g-danger", Theme.DANGER},
				{"-g-scrollbar-thumb", Theme.SCROLLBAR_THUMB}};
		for (Object[] entry : entries) {
			Color color = (Color) entry[1];
			Region chip = new Region();
			chip.setMinSize(44, 32);
			chip.setPrefSize(44, 32);
			chip.setStyle("-fx-background-color: " + ColorMath.toHex(color)
					+ "; -fx-background-radius: 8px; -fx-border-color: #2a303b; -fx-border-radius: 8px;");
			Label name = new Label((String) entry[0]);
			name.getStyleClass().add("showcase-state");
			swatches.getChildren().add(new VBox(4, chip, name));
		}
		return section("Tokens — src/index.css .dark + @heroui/styles dark theme (resolved to sRGB)",
				swatches);
	}

	private static VBox buttons() {
		FlowPane variants = wrap(8, 8,
				new Button("Primary"),
				new Button("Secondary", Button.Variant.SECONDARY),
				new Button("Tertiary", Button.Variant.SECONDARY),
				new Button("Outline", Button.Variant.OUTLINE),
				new Button("Ghost", Button.Variant.GHOST),
				new Button("Danger", Button.Variant.DESTRUCTIVE),
				new Button("Danger soft", Button.Variant.DESTRUCTIVE_SOFT),
				new Button("Link", Button.Variant.LINK));
		HBox sizes = row(
				new Button("Small", Button.Variant.DEFAULT, Button.Size.SM),
				new Button("Default"),
				new Button("Large", Button.Variant.DEFAULT, Button.Size.LG));
		HBox icons = row(
				new Button("Microphone").icon(Icons.MicrophoneIcon),
				new Button("", Button.Variant.SECONDARY, Button.Size.ICON).icon(Icons.GearSix),
				new Button("", Button.Variant.GHOST, Button.Size.ICON).icon(Icons.Play),
				new Button("Bold icon", Button.Variant.SECONDARY).icon(Icons.PlayIcon, "bold"),
				new Button("", Button.Variant.DESTRUCTIVE, Button.Size.ICON).icon(Icons.Trash));
		Button disabled = new Button("Disabled");
		disabled.setDisable(true);
		Button pressed = new Button("Ghost pressed", Button.Variant.GHOST);
		pressed.setAriaPressed(true);
		return section("Button — src/components/ui/button.tsx, @heroui/styles button.css",
				state("variants", variants),
				state("sizes", sizes),
				state("icons", icons),
				state("disabled / aria-pressed ghost", row(disabled, pressed)));
	}

	private static VBox toggles() {
		HBox states = row(new Toggle("Selected").pressed(true), new Toggle("Unselected"),
				new Toggle("Icon").icon(Icons.CaretDown));
		ToggleGroup group = new ToggleGroup(ToggleGroup.Mode.SINGLE);
		group.item("left", "Left", Icons.AlignLeft);
		group.item("center", "Center", Icons.AlignCenterHorizontal);
		group.item("right", "Right", Icons.AlignRight);
		group.setSelected("center");
		ToggleGroup detached = new ToggleGroup(ToggleGroup.Mode.SINGLE).detached();
		detached.item("a", "A");
		detached.item("b", "B");
		detached.setSelected("a");
		return section("Toggle / ToggleGroup — toggle.tsx, toggle-group.tsx",
				state("toggle states", states),
				state("single toggle group (attached)", group),
				state("detached", detached));
	}

	private static VBox switches() {
		Switch on = new Switch("On");
		on.setChecked(true);
		Switch disabled = new Switch("Disabled");
		disabled.setDisable(true);
		return section("Switch — switch.tsx, switch.css",
				row(new Switch("Off"), on, disabled, new Switch("Small", Switch.Size.SM, true),
						new Switch("Large", Switch.Size.LG, true)));
	}

	private static VBox sliders() {
		Slider bare = new Slider().range(0, 100).step(1);
		bare.setValue(36);
		bare.setPrefWidth(280);
		Slider control = new Slider("Volume").range(0, 200).step(5).sliderControl();
		control.setValue(80);
		control.setPrefWidth(280);
		control.formatter(value -> Math.round(value) + "%");
		Slider disabled = new Slider("Disabled").range(0, 100);
		disabled.setValue(50);
		disabled.setDisable(true);
		disabled.setPrefWidth(280);
		return section("Slider / SliderControl — slider.tsx, SliderControl.tsx",
				wrap(24, 12, state("bare", bare),
						state("SliderControl (label + formatted output)", control),
						state("disabled", disabled)));
	}

	private static VBox fields() {
		Input input = new Input("Project name");
		input.setPrefWidth(220);
		Input filled = new Input();
		filled.setText("Screen recording 2026-10-27");
		filled.setPrefWidth(220);
		Input disabled = new Input("Disabled");
		disabled.setDisable(true);
		disabled.setPrefWidth(220);
		dev.gravaai.ui.kit.Label required = new dev.gravaai.ui.kit.Label("Project name").required();
		dev.gravaai.ui.kit.Label invalid = new dev.gravaai.ui.kit.Label("Resolution").invalid();
		TextArea area = new TextArea("Notes");
		area.setPrefSize(320, 72);
		return section("Input / Label / TextArea — input.tsx, label.tsx",
				row(state("label required", required), state("label invalid", invalid)),
				state("inputs", row(input, filled, disabled)),
				state("textarea", area));
	}

	private static VBox cards() {
		Card card = new Card();
		card.setPrefWidth(320);
		card.getChildren().addAll(
				new Card.Header(new Card.Title("Recording"), new Card.Description("Today, 18:04")),
				new Card.Content(new Label("1920x1080 · 60 fps · 24:31")),
				new Card.Footer(new Button("Open", Button.Variant.SECONDARY, Button.Size.SM)));
		Card secondary = new Card(new Card.Header(new Card.Title("Surface secondary")));
		secondary.getStyleClass().add("card--secondary");
		secondary.setPrefWidth(240);
		return section("Card — card.tsx, card.css", row(card, secondary));
	}

	private static VBox separators() {
		Separator vertical = Separator.vertical();
		vertical.setMinHeight(24);
		HBox verticalRow = new HBox(12, new Label("Left"), vertical, new Label("Right"));
		verticalRow.setAlignment(Pos.CENTER_LEFT);
		return section("Separator — separator.tsx", new Separator(), verticalRow);
	}

	private static VBox skeletons() {
		HBox bars = new HBox(12);
		for (double width : new double[] {120, 80, 160, 60}) {
			bars.getChildren().add(new Skeleton(width, 16));
		}
		HBox cards = new HBox(12);
		for (int i = 0; i < 3; i++) {
			VBox card = new VBox(8, new Skeleton(180, 100), new Skeleton(120, 14), new Skeleton(90, 14));
			card.setPadding(new Insets(12));
			card.setStyle("-fx-background-color: -g-surface; -fx-background-radius: 16px;");
			cards.getChildren().add(card);
		}
		return section("Skeleton — skeleton.tsx (shimmer 2 s linear infinite)",
				state("bars", bars), state("cards", cards));
	}

	private static VBox selects() {
		Select resolution = new Select();
		resolution.placeholder("Resolution");
		resolution.item("1080p60", "1080p · 60 fps");
		resolution.item("1080p30", "1080p · 30 fps");
		resolution.item("720p60", "720p · 60 fps");
		resolution.group("Presets");
		resolution.item("4k30", "4K · 30 fps");
		resolution.separator();
		resolution.item("custom", "Custom…");
		resolution.value("1080p60");
		resolution.setPrefWidth(240);
		Select disabled = new Select();
		disabled.placeholder("Disabled");
		disabled.item("a", "Option");
		disabled.setPrefWidth(240);
		disabled.setDisable(true);
		return section("Select — select.tsx (in-window popup, keyboard navigable)",
				row(resolution, disabled));
	}


	private static VBox tabsSection() {
		Tabs tabs = new Tabs();
		tabs.addTab("general", "General", Icons.GearSix);
		tabs.addTab("timeline", "Timeline");
		tabs.addTab("export", "Export");
		tabs.content("general", new Label("General settings panel content"));
		tabs.content("timeline", new Label("Timeline settings panel content"));
		tabs.content("export", new Label("Export settings panel content"));
		tabs.setValue("general");
		tabs.setPrefWidth(420);
		return section("Tabs — tabs.tsx (indicator 250 ms ease-out-fluid)", tabs);
	}

	private static VBox accordions() {
		Accordion accordion = new Accordion();
		accordion.addItem("What does Gravaai record?",
				new Label("Screen, microphone, system audio, webcam, cursor and keystrokes."));
		accordion.addItem("Where are recordings stored?",
				new Label("In the recordings folder inside the Gravaai user data directory."));
		accordion.addItem("Keyboard shortcuts",
				new Label("Global shortcuts are configured in Settings → Global shortcuts."));
		accordion.setPrefWidth(560);
		return section("Accordion — accordion.tsx (panel 200 ms ease-out-quad)", accordion);
	}

	private static VBox choices() {
		ChoiceGroup format = new ChoiceGroup();
		format.item("mp4", "MP4");
		format.item("gif", "GIF");
		format.item("mkv", "MKV");
		format.setValue("mp4");
		ChoiceGroup quality = new ChoiceGroup();
		quality.item("low", "Low");
		quality.item("medium", "Medium");
		quality.item("high", "High");
		quality.item("ultra", "Ultra", Icons.MagicWand);
		quality.setValue("high");
		return section("ChoiceGroup — choice-group.tsx, tag.css",
				state("format", format), state("quality", quality));
	}

	private static VBox colorPickers() {
		ColorPicker accent = new ColorPicker("Accent", "#2fb3a3");
		accent.colors("#2fb3a3", "#e5656e", "#e8b339", "#3ecf8e", "#2a303b", "#ffffff");
		ColorPicker compact = new ColorPicker("Background", "#232833");
		compact.compact();
		return section("ColorPicker — color-picker.tsx (HSB area + hue slider + presets + hex)",
				row(accent, compact));
	}

	private static VBox meters() {
		AudioLevelMeter quiet = new AudioLevelMeter();
		quiet.setLevel(30);
		quiet.setPrefWidth(200);
		AudioLevelMeter loud = new AudioLevelMeter();
		loud.setLevel(72);
		loud.setPrefWidth(200);
		AudioLevelMeter clipping = new AudioLevelMeter();
		clipping.setLevel(94);
		clipping.setPrefWidth(200);
		return section("AudioLevelMeter — audio-level-meter.tsx, meter.css (accent/warning/danger)",
				wrap(24, 12, state("30", quiet), state("72", loud), state("94", clipping)));
	}

	private static VBox clamps() {
		ContentClamp clamp = new ContentClamp(
				"recording-2026-10-27-at-18-04-31-screen-and-microphone-and-system-audio.mkv");
		ContentClamp shortText = new ContentClamp("Short label");
		MarqueeText marquee = new MarqueeText(
				"Source selector hover marquee: a very long window title that does not fit");
		marquee.setPrefWidth(220);
		marquee.setMaxWidth(220);
		return section("ContentClamp / MarqueeText — content-clamp.tsx, MarqueeText.tsx",
				row(state("truncated + tooltip", clamp), state("short", shortText)),
				state("marquee (hover to scroll)", marquee));
	}

	private static VBox overlays() {
		Label status = new Label("No action yet");
		status.getStyleClass().add("text-muted");
		Button menuTrigger = new Button("Actions menu", Button.Variant.SECONDARY);
		DropdownMenu menu = new DropdownMenu(menuTrigger);
		menu.label("RECORDING");
		menu.item("Start recording", Icons.Play, "Ctrl+R", () -> status.setText("Start recording"));
		menu.item("Pause recording", Icons.Pause, "Ctrl+P", () -> status.setText("Pause recording"));
		menu.item("Open project", Icons.FolderOpen, null, () -> status.setText("Open project"));
		menu.separator();
		menu.label("EDIT");
		menu.dangerItem("Delete project", () -> status.setText("Delete project"));

		Button popoverTrigger = new Button("Popover", Button.Variant.SECONDARY);
		Popover popover = new Popover(popoverTrigger);
		popover.content(new Label("Popover content (p-4, radius 24)"),
				new Button("Inner action", Button.Variant.SECONDARY, Button.Size.SM));

		Button tooltipTrigger = new Button("Tooltip (1500 ms)", Button.Variant.SECONDARY);
		Tooltip tooltip = Tooltip.install(tooltipTrigger, "This is the HeroUI tooltip");
		tooltip.offset(8);
		Button hudTooltipTrigger = new Button("HudTooltip (300 ms)", Button.Variant.GHOST);
		HudTooltip.attach(hudTooltipTrigger, "HUD tooltip label");

		Button dialogTrigger = new Button("Open dialog", Button.Variant.SECONDARY);
		dialogTrigger.setOnAction(event -> {
			Dialog dialog = new Dialog("Remove project?");
			dialog.description("The project will be moved to the trash. This can be undone from there.")
					.content(new Label("Screen recording 2026-10-27 · 24:31"))
					.footer(new Button("Cancel", Button.Variant.SECONDARY, Button.Size.SM),
							new Button("Remove", Button.Variant.DESTRUCTIVE, Button.Size.SM));
			dialog.show(dialogTrigger);
		});

		HBox toastButtons = row();
		for (Toast.Variant variant : Toast.Variant.values()) {
			String label = switch (variant) {
				case DEFAULT -> "Default toast";
				case ACCENT -> "Accent toast";
				case SUCCESS -> "Success toast";
				case WARNING -> "Warning toast";
				case DANGER -> "Danger toast";
			};
			Button button = new Button(label, Button.Variant.SECONDARY, Button.Size.SM);
			button.setOnAction(event -> Toast.show(button, label)
					.description("In-window toast stack, bottom end")
					.variant(variant));
			toastButtons.getChildren().add(button);
		}

		return section("Overlays — DropdownMenu, Popover, Tooltip/HudTooltip, Dialog, Toast (all in-window)",
				state("dropdown menu", row(menuTrigger, status)),
				state("popover", popoverTrigger),
				state("tooltips", row(tooltipTrigger, hudTooltipTrigger)),
				state("dialog", dialogTrigger),
				state("toasts", toastButtons));
	}

	/** Standalone launcher used for development and the P1.3 screenshots. */
	public static final class Main extends Application {
		@Override
		public void start(Stage stage) {
			show(stage);
		}

		public static void main(String[] args) {
			launch(args);
		}
	}
}

