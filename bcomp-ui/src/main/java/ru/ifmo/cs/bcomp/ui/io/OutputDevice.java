/*
 * $Id$
 */

package ru.ifmo.cs.bcomp.ui.io;

import java.awt.Component;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import javax.swing.JCheckBox;
import javax.swing.JSlider;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import ru.ifmo.cs.bcomp.IOCtrl;

/**
 *
 * @author Dmitry Afanasiev <KOT@MATPOCKuH.Ru>
 */
public abstract class OutputDevice extends IODevice {
	private Thread timer = null;
	private long sleeptime = 100;
	private volatile boolean poweredon = true;

	public OutputDevice(final IOCtrl ioctrl, final String title) {
		super(ioctrl, title);
	}

	protected abstract void actionPerformed(int value);

	protected Component getSleepSlider() {
		JSlider slider = new JSlider(JSlider.HORIZONTAL, 0, 3, 2);
		slider.addChangeListener(new ChangeListener() {
			public void stateChanged(ChangeEvent e) {
				JSlider source = (JSlider)e.getSource();
				sleeptime = (int)Math.pow(10, source.getValue());
			}
		});

		return slider;
	}

	protected Component getPowerChkBox() {
		JCheckBox power = new JCheckBox("Вкл", true);
		power.addItemListener(new ItemListener() {
			public void itemStateChanged(ItemEvent e) {
				switch(e.getStateChange()) {
				case ItemEvent.SELECTED:
					poweredon = true;
					break;

				case ItemEvent.DESELECTED:
					poweredon = false;
					break;
				}
			}
		});

		return power;
	}

	@Override
	public void activate() {
		super.activate();

		if (timer == null) {
			timer = new Thread(new Runnable() {
				public void run() {
					ioctrl.setFlag();

					for (;;) {
						try {
							Thread.sleep(sleeptime);
						} catch (Exception ex) { }

						if (!poweredon)
							continue;

						if (ioctrl.getFlag() == 0) {
							actionPerformed(ioctrl.getData());
							ioctrl.setFlag();
						}
					}
				}
			}, title);

			timer.start();
		}

	}
}
