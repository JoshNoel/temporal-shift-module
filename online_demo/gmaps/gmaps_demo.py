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
DEF_WIDTH = 400
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


MAP_HTML = """
<html>
    <head>
        <title>Gesture Map</title>
        <meta name="viewport" content="initial-scale=1.0">
        <meta charset="utf-8">
    </head>
    <body>
        <div style="height:600px; width:400px;">
            <div id="map" style="max-width:none; height:100%"></div>
            {script}
        </div>
    </body>
</html>
"""

JS_FMT_STR = ["""
<script type="text/Javascript">
    // Setup map
    var map = null;
    function initMap() {{
        map = new google.maps.Map(document.getElementById('map'), {{
            center: {{ lat: {pos[0]}, lng: {pos[1]} }},
            zoom: {zoom}
            }});
        console.log("Map Initialized")
    }}
""",
"""
    // Setup python callbacks
    var kernel = IPython.notebook.kernel;
    var callbacks = {
        iopub : {
            output : update_map,
        }
    }
    function update_map(data_str) {
        console.log("Update Map");
        var data = null;
        try {
            data = JSON.parse(data_str.content.text.trim());
        } catch(err) {
            console.log("Error parsing output:");
            console.log(data_str.content);
        }
        var velocity = data[0];
        var zoom = data[1];
        console.log("velocity: ", velocity);
        console.log("zoom: ", zoom);
        map.panBy(velocity[0], velocity[1]);
        map.setZoom(zoom);
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
"""]

def get_html(pos, zoom, api_key):
    #return "<html><div>HELLO WORLD</div></html>"
    javascript =  JS_FMT_STR[0].format(pos=pos, zoom=zoom)
    javascript += JS_FMT_STR[1]
    javascript += JS_FMT_STR[2].format(api_key=api_key)
    html = MAP_HTML.format(script=javascript)
    return html

class gmaps_wrapper:
    """
    api_key - Google Maps Javascript API Key
    """
    def __init__(self, api_key):
        clear_output()
        self.api_key = api_key
        self.zoom = DEF_ZOOM
        self.velocity = [0.0, 0.0]
        self.pos = list(DEF_START_LOC)
        self.tsm_thread = None
        self.last_zoom_time = 0
        self.last_gesture_time = 0
        self.last_gesture = 2 # No Gesture

    # Printing returns the current position to javascript
    def poll_pos(self):
        data = [self.velocity, self.zoom]
        print(str(data), end="")

    def callback(self, output_idx):
        gesture_found = True
        swipe_direction = 0 # Horizontal
        sgn = 0 # No movement
        zoom_delta = 0
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
        elif gesture == "Thumb Up":
            reset = True
        elif gesture == "Zooming In With Two Fingers":
            if cur_time - self.last_zoom_time >= ZOOM_GAP:
                zoom_delta = 1
        elif gesture == "Zooming Out With Two Fingers":
            if cur_time - self.last_zoom_time >= ZOOM_GAP:
                zoom_delta = -1
        else:
            gesture_found = False


        if reset:
            self.last_gesture_time = 0
            self.last_zoom_time = 0
            self.velocity = [0, 0]
            self.zoom = DEF_ZOOM
        elif stop:
            self.last_gesture_time = 0
            self.velocity = [0, 0]
        elif gesture_found and self.last_gesture == gesture or cur_time - self.last_gesture_time >= GAP_CHANGE:
            # Only account for new gestures if GAP_CHANGE has passed

            # Send zoom delta
            if zoom_delta != 0:
                self.zoom += zoom_delta
                self.last_zoom_time = cur_time

            # Increase velocity according to swipe
            if sgn != 0:
                if self.last_gesture != gesture:
                    self.velocity[swipe_direction] = 0
                self.velocity[swipe_direction] += sgn*ACCEL_SWIPE

        # Decelerate
        for i in range(len(self.velocity)):
            if self.velocity[i] > 0:
                sgn = -1
            elif self.velocity[i] < 0:
                sgn = 1
            else:
                sgn = 0
            self.velocity[i] += sgn*ACCEL_SLOW
            # If slowing flipped sign, clamp to 0
            if self.velocity[i]*sgn > 0:
                self.velocity[i] = 0

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
