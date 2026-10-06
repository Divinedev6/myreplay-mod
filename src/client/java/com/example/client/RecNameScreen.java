package com.example.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class RecNameScreen extends Screen {
	private EditBox nameBox;
	private String error = "";

	public RecNameScreen() {
		super(Component.literal("Save recording"));
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int cy = this.height / 2;

		String defaultName = "rec_" + LocalDateTime.now()
			.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

		nameBox = new EditBox(this.font, cx - 100, cy - 10, 200, 20, Component.literal("Name"));
		nameBox.setMaxLength(40);
		nameBox.setValue(defaultName);
		this.addRenderableWidget(nameBox);
		this.setInitialFocus(nameBox);

		this.addRenderableWidget(Button.builder(Component.literal("Save"), btn -> doSave())
			.bounds(cx - 100, cy + 20, 98, 20)
			.build());

		this.addRenderableWidget(Button.builder(Component.literal("Discard"), btn -> {
			ExampleModClient.discardRecording();
			this.onClose();
		}).bounds(cx + 2, cy + 20, 98, 20).build());
	}

	private void doSave() {
		String name = nameBox.getValue().trim().replaceAll("[^A-Za-z0-9_-]", "_");
		if (name.isEmpty()) {
			error = "Please type a name";
			return;
		}
		try {
			ExampleModClient.saveFrames(name);
			int ticks = ExampleModClient.frameCount();
			Minecraft mc = Minecraft.getInstance();
			this.onClose();
			if (mc.player != null) {
				mc.player.displayClientMessage(
					Component.literal("Saved: " + name + " (" + ticks + " ticks)"), false);
			}
		} catch (IOException e) {
			error = "Save error: " + e.getMessage();
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);

		int cx = this.width / 2;
		int cy = this.height / 2;

		String title = "Save recording (" + (ExampleModClient.frameCount() / 20) + " sec)";
		graphics.drawString(this.font, title, cx - this.font.width(title) / 2, cy - 35, 0xFFFFFFFF, true);

		if (!error.isEmpty()) {
			graphics.drawString(this.font, error, cx - this.font.width(error) / 2, cy + 50, 0xFFFF5555, true);
		}
	}
}
