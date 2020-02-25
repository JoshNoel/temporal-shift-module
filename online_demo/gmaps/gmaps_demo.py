import sys
sys.path.append('../../')
sys.path.append('../../online_demo/')
import online_demo.main as online_demo
from IPython.display import HTML, display, clear_output
from threading import Thread
import time

category_dict = {idx: action for idx,action in enumerate(online_demo.catigories)}

# Stata Center
DEF_START_LOC = (42.3616,-71.0906)

# Map size
DEF_WIDTH = 600
DEF_HEIGHT = 600

# Default Zoom
DEF_ZOOM = 17

# Panning acceleration
ACCEL_SWIPE = .1
ACCEL_SLOW  = ACCEL_SWIPE/10

# Allow time gap between gestures (some easily follow each other without meaning)
GAP_CHANGE = 2.0

# Minimum time between any zoom gesture to allow map to update
ZOOM_GAP = 2.0
ZOOM_MIN_TIME = 1.0

# Seconds one must continuously drum fingers to toggle streetview. Avoids quick entrance & exit.
STREETVIEW_GAP = 1.0
STREETVIEW_OVERLAY_GAP = 2.0

# Seconds to follow streetview links
STREETVIEW_LINK_GAP = 2.5

# Streetview controls
S_ACCEL_SWIPE = [.09, .07] # [heading accel, pitch accel]
S_ACCEL_SLOW = [.01, .008]

S_DEF_ZOOM = 1.58
S_ACCEL_ZOOM = 0.01
S_ZOOM_SLOW = .001


MAP_HTML = """`
    <head>
        <title>Gesture Map</title>
        <meta name="viewport" content="initial-scale=1.0">
        <meta charset="utf-8">
    </head>
    <body>
        <div style="height:100%; width:100%;">
            <div id="map" style="height:100%"></div>
        </div>
    </body>
`
"""

JS_FMT_STR = ["""
<html>
<script type="text/Javascript">
    // Open new window for map
    var win = window.open("", "TSM Google Maps Navigator", "toolbar=no, location=no, directories=no, status=no, menubar=no, resizable=yes, width={width}, height={height}");
    win.document.body.innerHTML = {html};
    // Setup map
    var map = null;
    var panorama = null;
    var overlay = null;
    var showingStreetViewOverlay = false;
    var showingStreetView = false;

    function log(val) {{
        console.log(val);
    }}

    function initMap() {{
        // Init map
        map = new google.maps.Map(win.document.getElementById('map'), {{
            center: {{ lat: {pos[0]}, lng: {pos[1]} }},
            zoom: {zoom},
            zoomControl: false,
            streetViewControl: false,
            rotateControl: false
        }});
     
        // Init center marker
        var icon = {{
            url: "http://maps.google.com/mapfiles/kml/shapes/man.png",
            scaledSize: new google.maps.Size(50, 50)
        }};
        center_marker = new google.maps.Marker({{
            position: {{ lat: {pos[0]}, lng: {pos[1]} }},
            map: map,
            title: "Center",
            icon: icon,
            opacity: 0.5
        }});
        center_marker.setVisible(false);

        // Init street view
        overlay = new google.maps.StreetViewCoverageLayer();

        // Init street view overlay
        panorama = map.getStreetView();
        panorama.setPosition({{ lat: {pos[0]}, lng: {pos[1]} }});
        panorama.setPov(/** @type {{google.maps.StreetViewPov}} */({{
              heading: 265,
              pitch: 0
        }}));
        console.log("Map Initialized")
    }}
    function toggleStreetViewOverlay() {{
        console.log("Toggling StreetView Overlay");
        showingStreetViewOverlay = !showingStreetViewOverlay;
        if (!overlay.getMap()) {{
            center_marker.setVisible(true);
            overlay.setMap(map);
        }} else {{
            overlay.setMap(null);
            center_marker.setVisible(false);
        }}
    }}
    function toggleStreetView() {{
        console.log("Toggling StreetView");
        showingStreetView = !showingStreetView;
        if (!panorama.getVisible()) {{
            panorama.setPosition(map.getCenter());
            center_marker.setVisible(false);
            panorama.setVisible(true);
        }} else {{
            map.setCenter(panorama.getPosition());
            panorama.setVisible(false);
        }}
    }}

""",
"""

    var diff0 = 0;
    var diff1 = 0;
    var chosen_link = 0;
    // Return link closest to current heading if move_direction == 1. Find farthest if move_direction == 0.
    function get_follow_link(heading, move_direction, links) {
        var opt_link = null;
        var opt_diff = (move_direction == 1) ? 360 : 0;
        for (var i = 0; i < links.length; i++) {
            mod_heading = heading % 360; 
            mod_link = links[i].heading % 360;
            var diff = mod_heading - mod_link;
            if (diff < 0) {
                diff = -diff;
            }
            if (diff > 180) {
                diff = 360 - diff;
            }
            if (i == 0)
                diff0 = diff;
            if (i == 1)
                diff1 = diff;

            if (move_direction == 1 && diff < opt_diff) {
                opt_link = i;
                opt_diff = diff;
            } else if (move_direction == -1 && diff > opt_diff) {
                opt_link = i;
                opt_diff = diff;
            }
        }
        chosen_link = opt_link;
        return links[opt_link];
    }

    // Setup python callbacks
    var kernel = IPython.notebook.kernel;
    var callbacks = {
        iopub : {
            output : update_map,
        }
    }
    function update_map(data_str) {
        log("Update Map");
        var data = null;
        try {
            data = JSON.parse(data_str.content.text.trim());
            log(data);
        } catch(err) {
            log("Error parsing output:");
            log(data_str.content);
        }
        var velocity = data[0];
        var zoom = data[1];
        var moveDirection = data[2];
        var streetOverlay = data[3] == 1;
        var streetView = data[4] == 1;
        var reset = data[5] == 1;
        log("s_zoom: ", panorama.getZoom());
        log("s_pov: ", panorama.getPov());

        log("velocity: ", velocity);
        log("zoom: ", zoom);
        log("moveDirection: ", moveDirection);
        log("Diff0: ", diff0);
        log("Diff1: ", diff1);
        log("Link: ", chosen_link);

        center_marker.setPosition(map.getCenter());
        if (streetOverlay != showingStreetViewOverlay) {
            toggleStreetViewOverlay();
        }
        if (streetView != showingStreetView) {
            toggleStreetView();
        }

        if (!showingStreetView) {
            map.panBy(velocity[0], velocity[1]);
            map.setZoom(zoom);
        } else {
            panorama.setZoom(zoom);
            if (reset) {
                panorama.setPov({heading: 265, pitch: 0});
            } else {
                var pov = panorama.getPov();
                if (moveDirection != 0) {
                    var follow_link = get_follow_link(pov.heading, moveDirection, panorama.getLinks());
                    panorama.setPano(follow_link.pano);
                    follow_link = get_follow_link(pov.heading, moveDirection, panorama.getLinks());
                    panorama.setPano(follow_link.pano);
                    follow_link = get_follow_link(pov.heading, moveDirection, panorama.getLinks());
                    panorama.setPano(follow_link.pano);
                } else {
                    panorama.setPov({heading: pov.heading+velocity[0], pitch: pov.pitch-velocity[1]});
                }
            }
        }
    }
    function poll_tsm() {
        if (map != null) {
            kernel.execute("gmaps.poll_pos()", callbacks);
        }
    }

    // ~30 Updates Per Second
    setInterval(poll_tsm, 33);
</script>
""",
"""
<script src="https://maps.googleapis.com/maps/api/js?key={api_key}&callback=initMap" async defer></script>
</html>
"""]

def get_html(pos, zoom, api_key):
    #return "<html><div>HELLO WORLD</div></html>"
    html = MAP_HTML
    javascript =  JS_FMT_STR[0].format(pos=pos, zoom=zoom, html=html, height=DEF_HEIGHT, width=DEF_WIDTH)
    javascript += JS_FMT_STR[1]
    javascript += JS_FMT_STR[2].format(api_key=api_key)
    return javascript

class gmaps_wrapper:
    """
    api_key - Google Maps Javascript API Key
    """
    def __init__(self, api_key):
        clear_output()
        self.api_key = api_key
        self.reset = False
        self.pos = list(DEF_START_LOC)
        self.velocity = [0.0, 0.0]
        self.zoom = DEF_ZOOM
        self.zoom_start = 0

        self.streetView = False
        self.streetViewOverlay = False
        self.toggle_streetViewOverlay_start = 0
        self.toggle_streetView_start = 0
        self.street_zoom = S_DEF_ZOOM
        self.street_zoom_velocity = 0.0
        self.move_direction = 0

        self.tsm_thread = None
        self.last_move_time = 0
        self.last_zoom_time = 0
        self.last_gesture_time = 0
        self.last_gesture = 2 # No Gesture

    # Printing returns the current position to javascript
    def poll_pos(self):
        # Update street zoom in python. Allows sharing of zoom parameter between map and streetView.
        self.street_zoom += self.street_zoom_velocity

        # Convert Python boolean to JS boolean
        reset = 1 if self.reset else 0
        streetViewOverlay = 1 if self.streetViewOverlay else 0
        streetView = 1 if self.streetView else 0
        zoom = self.zoom if not self.streetView else self.street_zoom

        data = [self.velocity, zoom, self.move_direction, streetViewOverlay, streetView, reset]
        self.reset = False
        self.move_direction = 0
        print(str(data), end="")

    # Only account for new gestures if GAP_CHANGE has passed
    # checks if appropriate time has passed to detect a new gesture
    def gap_passed(self, gesture, cur_time):
        return self.last_gesture == gesture or cur_time - self.last_gesture_time >= GAP_CHANGE

    def callback(self, output_idx):
        gesture_found = True
        swipe_direction = 0 # Horizontal
        sgn = 0 # No movement
        zoom_delta = 0
        move_direction = 0
        streetView_gesture = False # Set if streetview gesture detected. Additional processing before actual toggle. See 'STREETVIEW_GAP' variable
        streetViewOverlay_gesture = False # Same as streetview_gesture, except for displaying overlay
        streetView_toggled = False
        streetViewOverlay_toggled = False
        stop = False
        reset = False
        cur_time = time.time()
        gesture = category_dict[output_idx]

        # Map moves opposite direction from swipe
        if gesture == "Swiping Left":
            swipe_direction = 0
            sgn = 1
        elif gesture == "Swiping Right":
            swipe_direction = 0
            sgn = -1
        elif gesture == "Swiping Down":
            swipe_direction = 1
            sgn = -1
        elif gesture == "Swiping Up":
            swipe_direction = 1
            sgn = 1
        elif gesture == "Stop Sign":
            stop = True
        elif gesture == "Thumb Down":
            reset = True
        elif gesture == "Zooming In With Full Hand" and not self.streetView:
            zoom_delta = 1
        elif gesture == "Zooming Out With Full Hand" and not self.streetView:
            zoom_delta = -1
        elif gesture == "Drumming Fingers":
            streetViewOverlay_gesture = True
            if self.toggle_streetViewOverlay_start == 0:
                self.toggle_streetViewOverlay_start = cur_time
            elif cur_time - self.toggle_streetViewOverlay_start >= STREETVIEW_OVERLAY_GAP:
                self.toggle_streetViewOverlay_start = 0
                self.streetViewOverlay = not self.streetViewOverlay
                streetViewOverlay_toggled = True
                if self.streetView:
                    streetView_toggled = True
                self.streetView = False
        elif gesture == "Thumb Up" and self.streetViewOverlay:
            streetView_gesture = True
            if self.toggle_streetView_start == 0:
                self.toggle_streetView_start = cur_time
            elif cur_time - self.toggle_streetView_start >= STREETVIEW_GAP:
                self.toggle_streetView_start = 0
                self.streetView = not self.streetView
                self.streetViewOverlay = False
                streetView_toggled = True
                streetViewOverlay_toggled = True
        elif gesture == "Zooming In With Full Hand" and self.streetView and cur_time - self.last_move_time >= STREETVIEW_LINK_GAP:
            move_direction = 1
        elif gesture == "Zooming Out With Full Hand" and self.streetView and cur_time - self.last_move_time >= STREETVIEW_LINK_GAP:
            move_direction = -1
        else:
            self.toggle_streetViewOverlay_start = 0
            self.toggle_streetView_start = 0
            gesture_found = False

        if reset:
            self.last_gesture_time = 0
            self.last_zoom_time = 0
            self.velocity = [0, 0]
            self.zoom = DEF_ZOOM
            self.street_zoom = S_DEF_ZOOM
            self.reset = True
        elif stop:
            self.last_gesture_time = 0
            self.velocity = [0, 0]
            self.street_zoom_velocity = 0
        elif streetView_toggled:
            self.velocity = [0, 0]
        elif not self.streetView and gesture_found and self.gap_passed(gesture, cur_time):
            # Reset streetView toggle interval
            if not streetView_gesture:
                self.toggle_streetView_start = 0
            if not streetViewOverlay_gesture:
                self.toggle_streetViewOverlay_start = 0

            # Send zoom delta
            if zoom_delta != 0 and cur_time - self.last_zoom_time >= ZOOM_GAP:
                if self.zoom_start == 0:
                    self.zoom_start = cur_time
                elif cur_time - self.zoom_start >= ZOOM_MIN_TIME:
                    self.zoom += zoom_delta
                    self.last_zoom_time = cur_time
                    self.zoom_time = 0

            # Increase velocity according to swipe
            if sgn != 0:
                if self.last_gesture != gesture:
                    self.velocity[swipe_direction] = 0
                self.velocity[swipe_direction] += sgn*ACCEL_SWIPE
        elif self.streetView and gesture_found and self.gap_passed(gesture, cur_time):
            if not streetView_gesture:
                self.toggle_streetView_start = 0
            if not streetViewOverlay_gesture:
                self.toggle_streetViewOverlay_start = 0

            if zoom_delta != 0:
                self.street_zoom_velocity = zoom_delta*S_ACCEL_ZOOM

            if sgn != 0:
                #if self.last_gesture != gesture:
                #    self.velocity[swipe_direction] = 0
                self.velocity[swipe_direction] += sgn*S_ACCEL_SWIPE[swipe_direction]

            # Handle following links
            if move_direction != 0:
                self.last_move_time = cur_time
                self.move_direction = move_direction

        # Decelerate Swipe
        for i in range(len(self.velocity)):
            if self.velocity[i] > 0:
                sgn = -1
            elif self.velocity[i] < 0:
                sgn = 1
            else:
                sgn = 0
            slow = ACCEL_SLOW if not self.streetView else S_ACCEL_SLOW[i]
            self.velocity[i] += sgn*slow
            # If slowing flipped sign, clamp to 0
            if self.velocity[i]*sgn > 0:
                self.velocity[i] = 0

        # Decelerate Zoom
        if self.street_zoom_velocity > 0:
            sgn = -1
        elif self.street_zoom_velocity < 0:
            sgn = 1
        else:
            sgn = 0
        self.street_zoom_velocity += sgn*S_ZOOM_SLOW
        if self.street_zoom_velocity*sgn > 0:
            self.street_zoom_velocity = 0

        if gesture_found:
            self.last_gesture = category_dict[output_idx]
            self.last_gesture_time = cur_time

    def show(self, start_loc=DEF_START_LOC, offset=(0,0)):
        self.pos = [start_loc[0]+offset[0], start_loc[1]+offset[1]]
        map_src = get_html(pos=self.pos, zoom=self.zoom, api_key=self.api_key)
        display(HTML(map_src))

    def run(self, target='cuda'):
        self.tsm_thread = Thread(target=online_demo.run, kwargs={"target":target, "print_log":False, "callback_fn":self.callback})
        self.tsm_thread.start()

    def stop(self):
        if self.tsm_thread:
            self.tsm_thread.join()
