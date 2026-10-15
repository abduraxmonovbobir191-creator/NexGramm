package org.telegram.ui;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BackupImageView;

// NexGramm: opens a profile photo full screen, growing out of the round photo on the page. PhotoViewer needs
// the place (with the peer id) to know whose photo it is; without it the viewer stays black.
final class NexAvatarViewer {

    private NexAvatarViewer() {
    }

    static void open(BaseFragment fragment, BackupImageView from, long dialogId, TLRPC.FileLocation big) {
        if (fragment.getParentActivity() == null || big == null || dialogId == 0) {
            return;
        }
        PhotoViewer.getInstance().setParentActivity(fragment);
        PhotoViewer.getInstance().openPhoto(big, new PhotoViewer.EmptyPhotoViewerProvider() {
            @Override
            public PhotoViewer.PlaceProviderObject getPlaceForPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index, boolean needPreview, boolean closing) {
                if (fileLocation == null || from == null || !from.isAttachedToWindow()) {
                    return null;
                }
                final int[] coords = new int[2];
                from.getLocationInWindow(coords);
                final PhotoViewer.PlaceProviderObject object = new PhotoViewer.PlaceProviderObject();
                object.viewX = coords[0];
                object.viewY = coords[1];
                object.parentView = from;
                object.imageReceiver = from.getImageReceiver();
                object.dialogId = dialogId;
                object.thumb = object.imageReceiver.getBitmapSafe();
                object.size = -1;
                object.radius = object.imageReceiver.getRoundRadius(true);
                return object;
            }

            @Override
            public void willHidePhotoViewer() {
                if (from != null) {
                    from.getImageReceiver().setVisible(true, true);
                }
            }
        });
    }
}
