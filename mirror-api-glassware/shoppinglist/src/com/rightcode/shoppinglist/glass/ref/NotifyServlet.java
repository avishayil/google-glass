/*
 * Copyright (C) 2013 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.rightcode.shoppinglist.glass.ref;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.mirror.Mirror;
import com.google.api.services.mirror.model.Location;
import com.google.api.services.mirror.model.MenuItem;
import com.google.api.services.mirror.model.Notification;
import com.google.api.services.mirror.model.NotificationConfig;
import com.google.api.services.mirror.model.TimelineItem;
import com.google.api.services.mirror.model.UserAction;
import com.google.common.collect.Lists;
import com.rightcode.shoppinglist.glass.AppController;
import com.rightcode.shoppinglist.glass.Constants;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Random;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Handles the notifications sent back from subscriptions
 * 
 * @author Jenny Murphy - http://google.com/+JennyMurphy
 */
public class NotifyServlet extends HttpServlet {
    private static final Logger LOG = Logger.getLogger(NotifyServlet.class.getSimpleName());

    private static final String[] CAT_UTTERANCES = { "<em class='green'>Purr...</em>", "<em class='red'>Hisss... scratch...</em>",
            "<em class='yellow'>Meow...</em>" };

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        LOG.info("**********Great, we got a notification");

        // Get the notification object from the request body (into a string so
        // we
        // can log it)
        BufferedReader notificationReader = new BufferedReader(new InputStreamReader(request.getInputStream()));
        String notificationString = "";

        String sCurrentLine;
        int lines = 0;
        while ((sCurrentLine = notificationReader.readLine()) != null) {
            notificationString += sCurrentLine;
            lines++;
            if (lines > 1000) {
                throw new IOException("Attempted to parse notification payload that was unexpectedly long.");
            }
        }
        // Count the lines as a very basic way to prevent Denial of Service
        // attacks
        // int lines = 0;
        // while (notificationReader.ready()) {
        // notificationString += notificationReader.readLine();
        // lines++;
        //
        // // No notification would ever be this long. Something is very wrong.
        // if (lines > 1000) {
        // throw new
        // IOException("Attempted to parse notification payload that was unexpectedly long.");
        // }
        // }

        LOG.info("got raw notification [" + notificationString + "]");

        // Respond with OK and status 200 in a timely fashion to prevent
        // redelivery
        response.setContentType("text/html");
        Writer writer = response.getWriter();
        writer.append("OK");
        writer.close();

        JsonFactory jsonFactory = new JacksonFactory();

        // If logging the payload is not as important, use
        // jacksonFactory.fromInputStream instead.
        Notification notification = jsonFactory.fromString(notificationString, Notification.class);

        LOG.info("Got a notification with ID: " + notification.getItemId());

        // Figure out the impacted user and get their credentials for API calls
        String userId = notification.getUserToken();
        Credential credential = AuthUtil.getCredential(userId);
        Mirror mirrorClient = MirrorClient.getMirror(credential);

        if (credential != null) {
            LOG.info("-----Access token[" + credential.getAccessToken() + "], Expires In " + credential.getExpiresInSeconds());
        } else {
            LOG.severe("-----Credential object is null for user:" + userId);
        }

        if (notification.getCollection().equals("locations")) {
            LOG.info("Notification of updated location");
            Mirror glass = MirrorClient.getMirror(credential);
            // item id is usually 'latest'
            Location location = glass.locations().get(notification.getItemId()).execute();

            LOG.info("New location is " + location.getLatitude() + ", " + location.getLongitude());
            MirrorClient.insertTimelineItem(
                    credential,
                    new TimelineItem()
                            .setText("Java Quick Start says you are now at " + location.getLatitude() + " by " + location.getLongitude())
                            .setNotification(new NotificationConfig().setLevel("DEFAULT")).setLocation(location)
                            .setMenuItems(Lists.newArrayList(new MenuItem().setAction("NAVIGATE"))));

            // This is a location notification. Ping the device with a timeline item
            // telling them where they are.
        } else if (notification.getCollection().equals("timeline")) {
            // Get the impacted timeline item
            TimelineItem timelineItem = null;
            // Eric.TODO, some strange error log found which end here but not further info, so use Try catch here to make sure we can got
            // the error details 2014-1-1, Event the timeline is deleted from timeline. below method won't return error but a incomplete
            // timeline item(with id and bundle_id, but not html, text)
            try {
                timelineItem = mirrorClient.timeline().get(notification.getItemId()).execute();
            } catch (Throwable t) {
                LOG.log(Level.SEVERE, t.getMessage(), t);
                throw new RuntimeException(t);
            }
            LOG.info("Notification impacted timeline item with ID: " + timelineItem.getId());
            if (notification.getUserActions() != null) {
                LOG.info("--------Action type & payload:" + notification.getUserActions().get(0).getType() + ":"
                        + notification.getUserActions().get(0).getPayload());
                // If it was a share, and contains a photo, update the photo's
                // caption to
                // acknowledge that we got it.
                if (notification.getUserActions().contains(new UserAction().setType("SHARE")) && timelineItem.getAttachments() != null
                        && timelineItem.getAttachments().size() > 0) {
                    LOG.info("It was a share of a photo. Updating the caption on the photo.");

                    String caption = timelineItem.getText();
                    if (caption == null) {
                        caption = "";
                    }

                    // Create a new item with just the values that we want to
                    // patch.
                    TimelineItem itemPatch = new TimelineItem();
                    itemPatch.setText("Java Quick Start got your photo! " + caption);

                    // Patch the item. Notice that since we retrieved the entire item above in order to access the caption, we could have just changed the text
                    // in place and used the update method, but we wanted to
                    // illustrate the
                    // patch method here.
                    mirrorClient.timeline().patch(notification.getItemId(), itemPatch).execute();
                } else if (notification.getUserActions().contains(new UserAction().setType("LAUNCH"))) {
                    LOG.info("It was a note taken with the 'take a note' voice command. Processing it.");

                    // Grab the spoken text from the timeline card and update
                    // the card with
                    // an HTML response (deleting the text as well).
                    String noteText = timelineItem.getText();
                    String utterance = CAT_UTTERANCES[new Random().nextInt(CAT_UTTERANCES.length)];

                    timelineItem.setText(null);
                    timelineItem.setHtml(makeHtmlForCard("<p class='text-auto-size'>" + "Oh, did you say " + noteText + "? " + utterance
                            + "</p>"));
                    timelineItem.setMenuItems(Lists.newArrayList(new MenuItem().setAction("DELETE")));

                    mirrorClient.timeline().update(timelineItem.getId(), timelineItem).execute();
                } else if (notification.getUserActions().get(0).getType().equals("CUSTOM")) {
                    UserAction ua = notification.getUserActions().get(0);
                    AppController appController = AppController.getInstance();
                    if (Constants.MENU_ID_MARK.equals(ua.getPayload())) {
                        appController.markOrUnMarkProduct(mirrorClient, userId, timelineItem, true);
                    } else if (Constants.MENU_ID_UNMARK.equals(ua.getPayload())) {
                        appController.markOrUnMarkProduct(mirrorClient, userId, timelineItem, false);
                    } else if (Constants.MENU_ID_STARTSHOPPING.equals(ua.getPayload())) {
                        appController.actionStartShopping(userId, timelineItem.getId());
                    } else if (Constants.MENU_ID_IC_FETCH.equals(ua.getPayload())) {
                        appController.actionFetchShoppingLists(userId, timelineItem.getId());
                    } else if (Constants.MENU_ID_FINISHSHOPPING.equals(ua.getPayload())) {
                        appController.actionFinishShopping(userId, timelineItem.getId());
                    } else if (Constants.MENU_ID_IC_RESTART.equals(ua.getPayload())) {
                        appController.actionRestart(userId, timelineItem.getId());
                    }else if (Constants.MENU_ID_IC_REFRESH.equals(ua.getPayload())) {
                        appController.actionRefresh(userId);
                    }
                } else if (notification.getUserActions().contains(new UserAction().setType("REPLY"))) {
                    LOG.info("I know you just reply my card");
                    String itemid = notification.getItemId();

                    TimelineItem item = mirrorClient.timeline().get(itemid).execute();

                    LOG.info("Is it what you just spoke?:[" + item.getText() + "]");
                } else {
                    LOG.warning("I don't know what to do with this notification, so I'm ignoring it.");
                }
            } else {
                LOG.warning("Update is not triggered by any user action, so I'm ignoring it.");
            }
        }
        LOG.info("*****Notification flow done");
    }

    /**
     * Wraps some HTML content in article/section tags and adds a footer identifying the card as originating from the Java Quick Start.
     * 
     * @param content
     *            the HTML content to wrap
     * @return the wrapped HTML content
     */
    private static String makeHtmlForCard(String content) {
        return "<article class='auto-paginate'>" + content + "<footer><p>Java Quick Start</p></footer></article>";
    }
}
