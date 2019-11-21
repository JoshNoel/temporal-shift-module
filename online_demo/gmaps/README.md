# TSM Google Maps Demo

An extension of **temporal-shift-module/online_demo**, which utilizes gesture recognition to navigate Google Maps.

## Setup Instructions
1. Follow setup instructions in **temporal-shift-module/online_demo**.

2. Get a Google Maps Javascript API Key following the [Google Tutorial](https://developers.google.com/maps/documentation/javascript/get-api-key)
	*  **Note**: As of 11/2019, the Javascript API is free to use for the first $200/month (~28,000 map loads) where a map load is generated once per execution of gmaps_wrapper.show().

3. Activate the environment where **temporal-shift-module/online_demo** was setup. Install the anaconda jupyter prequisite.
```
conda install jupyter
```

4. **Open Gmaps.ipynb** in the python environment in which **temporal-shift-module/online_demo** was setup.
```
# Launch notebook. Navigate to open Gmaps.ipynb in opened browser window.
jupyter notebook
```

5. **Configure the Notebook**. The first cell contains a placeholder **`<API_KEY>`**, where API key from step (2) should be pasted. If necssary change the **target** argument in the second cell ('cuda', 'opencl', or 'llvm' for CPU-only).

6. **Run the Demo**. Running the first cell with display the map. Subsequently, running the second cell will launch **temporal-shift-module/online_demo** from which gestures are pushed.

## Using the Maps
Currently the map supports the following gestures:

- Swipe Up/Down/Left/Right := Pans the map
- Zooming In/Out With Two Fingers := Changes zoom level of the map
- Stop Sign := Stops any current movement
- Thumbs Up := Stops any current movement and resets the zoom level to default.