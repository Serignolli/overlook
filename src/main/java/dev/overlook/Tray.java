package dev.overlook;

import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.net.URL;

/**
 * Ícone na bandeja do sistema com "Abrir painel" e "Encerrar". Quando o app é aberto por
 * duplo clique (sem terminal), é por aqui que a pessoa o fecha. Em sistemas sem bandeja,
 * não faz nada: o painel tem o mesmo botão de encerrar nas configurações.
 */
final class Tray {

    private Tray() {
    }

    static void install(String url, Runnable open, Runnable quit) {
        try {
            if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
                return;
            }
            SystemTray tray = SystemTray.getSystemTray();
            int size = tray.getTrayIconSize().width;
            URL resource = Tray.class.getResource(size > 32 ? "/icon/overlook-64.png"
                    : size > 16 ? "/icon/overlook-32.png" : "/icon/overlook-16.png");
            if (resource == null) {
                return;
            }
            Image image = Toolkit.getDefaultToolkit().getImage(resource);

            PopupMenu menu = new PopupMenu();
            MenuItem openItem = new MenuItem("Abrir painel");
            openItem.addActionListener(e -> open.run());
            MenuItem quitItem = new MenuItem("Encerrar Overlook");
            quitItem.addActionListener(e -> quit.run());
            menu.add(openItem);
            menu.addSeparator();
            menu.add(quitItem);

            TrayIcon icon = new TrayIcon(image, "Overlook · " + url, menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> open.run());
            tray.add(icon);
        } catch (Exception | LinkageError e) {
            // bandeja indisponível (ex.: alguns ambientes Linux): segue sem ela
        }
    }
}
