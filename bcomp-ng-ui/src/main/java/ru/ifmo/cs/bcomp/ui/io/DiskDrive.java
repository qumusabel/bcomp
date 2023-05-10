package ru.ifmo.cs.bcomp.ui.io;

import ru.ifmo.cs.bcomp.IOCtrl;
import ru.ifmo.cs.bcomp.IOCtrlAdv;
import ru.ifmo.cs.bcomp.ui.components.RegisterView;
import ru.ifmo.cs.components.DataDestination;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;

import static ru.ifmo.cs.bcomp.ui.components.DisplayStyles.FONT_COURIER_PLAIN_12;

public class DiskDrive extends IODevice {
    private JFileChooser fileChooser;

    private DiskDriveController diskCtrl;
    private IOCtrlAdv ioCtrlAdv;

    public DiskDrive(IOCtrlAdv ioCtrl) {
        super(ioCtrl, "disk");

        ioCtrlAdv = ioCtrl;

        diskCtrl = new DiskDriveController(ioCtrlAdv);
        fileChooser = new JFileChooser();
    }


    @Override
    protected Component getContent() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setPreferredSize(new Dimension(600, 250));

        GridBagConstraints constraints = new GridBagConstraints() {{
            gridy = 0;
            gridx = 3;
            gridwidth = GridBagConstraints.REMAINDER;
        }};

        panel.add(new JButton("Insert Image"){{
            setFont(FONT_COURIER_PLAIN_12);
            setFocusable(false);
            addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    int result = fileChooser.showOpenDialog(null);
                    if (result == JFileChooser.APPROVE_OPTION) {
                        diskCtrl.setImageFile(fileChooser.getSelectedFile().toPath());
                    }
                }
            });
        }}, constraints);

        constraints.gridy++;
        constraints.insets.top += 15;
        constraints.insets.left = 0;

        return panel;
    }

    private static class DiskDriveController {
        private IOCtrlAdv ioCtrl;

        private int diskPos = 0;
        private static final int DISKPOS_BYTE_MASK = 0x00FF, DISKPOS_SECTOR_MASK = 0xFF00;
        private byte[] data = new byte[65536];

        private Mode mode = Mode.DATA;
        private boolean isDead = false;

        Path imageFile = null;

        public DiskDriveController(IOCtrlAdv ioCtrl) {
            System.out.println("Disk Drive go!");

            this.ioCtrl = ioCtrl;

            // DR1 is a mode register
            ioCtrl.addWriteDestination(1, new DataDestination() {
                @Override
                public void setValue(long value) {
                    ioCtrl.unsetReady();

                    Mode prevMode = mode;
                    if ((mode = Mode.getByNo(value)) == null) {
                        die();
                        return;
                    }

                    if (prevMode == mode) {
                        System.out.println("DR1: mode is same");
                        setReady();
                        return;
                    }

                    switch (mode) {
                        case DATA:
                            ioCtrl.setData(data[diskPos]);
                            break;
                        case BYTE_N:
                            ioCtrl.setData(diskPos & DISKPOS_BYTE_MASK);
                            break;
                        case SECTOR_N:
                            ioCtrl.setData((diskPos & DISKPOS_SECTOR_MASK) >> 8);
                            break;
                        case SEEK:
                        case CTRL:
                            ioCtrl.setData(0);
                            break;
                    }
                    setReady();
                }
            });

            // DR0 is mode-dependent register
            ioCtrl.addWriteDestination(0, new DataDestination() {
                @Override
                public void setValue(long value) {
                    System.out.println("DR0 set value");
                    ioCtrl.unsetReady();

                    switch (mode) {
                        case DATA:
                            if (diskPos == 65536) {
                                die();
                                return;
                            }
                            data[diskPos++] = (byte) ioCtrl.getData();
                            break;
                        case SEEK:
                            int arg = (int) ioCtrl.getData();
                            int offset = arg > 127 ? arg - 256 : arg;
                            if (diskPos + offset < 0 || diskPos + offset >= 65536) {
                                die();
                                return;
                            }
                            diskPos += offset;
                            break;
                        case BYTE_N:
                            diskPos = diskPos & DISKPOS_SECTOR_MASK | (int) ioCtrl.getData();
                            break;
                        case SECTOR_N:
                            diskPos = diskPos & DISKPOS_BYTE_MASK | (int) ioCtrl.getData() << 8;
                            break;
                        case CTRL:
                            if (ioCtrl.getData() == 0x69) {
                                doSync();
                            }
                            break;
                    }

                    setReady();
                }
            });

            ioCtrl.addReadDestination(0, new DataDestination() {
                @Override
                public void setValue(long value) {
                    if (mode == Mode.DATA) {
                        ioCtrl.unsetReady();
                        ioCtrl.getRegisters()[0].setValue(data[++diskPos]);
                        setReady();
                    }
                }
            });
        }

        private void setImageFile(Path imageFile) {
            ioCtrl.unsetReady();
            this.imageFile = imageFile;
            if (imageFile == null) {
                return;
            }

            byte[] image;
            try {
                image = Files.readAllBytes(imageFile);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            data = Arrays.copyOf(image, 65536);

            diskPos = 0;
            ioCtrl.setData(data[diskPos]);
            setReady();
        }

        private void die() {
            System.out.println("Oh no! Disk died!");
            ioCtrl.getRegisters()[0].setValue(0xFF);
            ioCtrl.getRegisters()[1].setValue(0xFF);
            isDead = true;
        }

        private void doSync() {
            if (imageFile == null) die();
            try {
                Files.write(imageFile, data, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private void setReady() {
            if (!isDead) ioCtrl.setReady();
        }

        private enum Mode {
            DATA(0x0, "Data"),          // 00 Data mode - DR0[RW] is data on disk
            SEEK(0xA, "Seek"),          // 0A Seek mode - DR0[RW] is offset
            BYTE_N(0xB, "ByteN"),       // 0B Byte Number mode - DR0[RW] is byte number in sector
            SECTOR_N(0xC, "SectorN"),   // 0C Sector Number mode - DR0[RW] is sector number
            CTRL(0xF, "Control");       // 0F Control mode - DR0[W] is control command code

            private static final Mode[] values = Mode.values();  // Cache

            private final long no;
            private final String desc;

            Mode(long no, String desc) {
                this.no = no;
                this.desc = desc;
            }

            public static Mode getByNo(long no) {
                for (Mode m: values) {
                    if (m.no == no) {
                        return m;
                    }
                }
                return null;
            }

            public String toString() { return this.desc; }
        }
        private enum CtrlCmd {}
        // Eject, Commit?, Rollback?
    }
}
