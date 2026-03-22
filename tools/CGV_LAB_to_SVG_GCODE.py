import sys
import math
import numpy as np
import matplotlib.pyplot as plt
import os
import argparse

"""
This script reads a file containing coordinates, applies Chaikin's corner cutting algorithm to smooth the path
and create the scribble path.,
It then saves the smoothed path as a numpy array (if selected) and as a plot in both PNG and SVG formats.
"""

def parse_args():
    parser = argparse.ArgumentParser(description="Convert an image to a crosshatch SVG.")
    parser.add_argument("image_path", type=str, help="Path to the input image.")
    parser.add_argument("--resolution", type=int, default=3, help="This define the number of subdivisions of the original path.")
    parser.add_argument("--save_np_array", type=int, default=0, help="Save NP array for Scribi? 0 no / 1 yes.")
    parser.add_argument("--save_gcode", type=int, default=0, help="Save G-code for CNC / GRBL? 0 no / 1 yes.")
    parser.add_argument("--save_svg", type=int, default=0, help="Save SVG? 0 no / 1 yes.")
    parser.add_argument("--paper_size", type=str, default='4', help="Paper size for the output (A5, A4, A3, A2, A1, A0). Default is A4.")
    parser.add_argument("--paper_margin", type=int, default=10, help="Margin in mm for the paper size (default 10mm).") # Still not working properly, must be debugged
    # New arguments for G-code control
    parser.add_argument("--feedrate", type=float, default=6000.0, help="XY Feedrate for drawing movements (mm/min)")
    parser.add_argument("--z_feedrate", type=float, default=2000.0, help="Z-axis feedrate for up/down movements (mm/min)")
    parser.add_argument("--z_safe", type=float, default=5.0, help="Z-axis safe height (mm) / Pen UP height")
    parser.add_argument("--z_draw", type=float, default=0.0, help="Z-axis drawing height (mm) / Pen DOWN height")

    return parser.parse_args()

default_params = {
    'resolution': 3,  # Number of subdivisions of the original path
    'save_np_array': 0,  # Save NP array for Scribi? 0 no / 1 yes
    'save_gcode': 1,  # Save G-code for CNC? 0 no / 1 yes
    'save_svg': 1,  # Save SVG? 0 no / 1 yes
    'paper_size': '4',  # Paper size for the output (A5, A4, A3, A2, A1, A0)
    'paper_margin': 10,  # Margin in mm for the paper size
    'feedrate': 6000.0,  # Default feedrate
    'z_feedrate': 2000.0,  # Default Z-axis feedrate for up/down movements
    'z_safe': 5.0,  # Default safe Z height
    'z_draw': 0.0,  # Default drawing Z height
}

def get_scale_factor(size, drawing_width, drawing_height, margin=10):
    """Return the scale factor to fit the drawing within the paper size with uniform margins."""
    sizes = {
        '5': (148, 210),  # A5
        '4': (210, 297),  # A4
        '3': (297, 420),  # A3
        '2': (420, 594),  # A2
        '1': (594, 841),  # A1
        '0': (841, 1189)  # A0
    }
    paper_width, paper_height = sizes.get(size, (210, 297))  # Default to A4
    
    # Calculate available drawing area (subtract margins)
    available_width = paper_width - 2 * margin
    available_height = paper_height - 2 * margin
    
    # Handle invalid dimensions
    if drawing_width <= 0 or drawing_height <= 0 or available_width <= 0 or available_height <= 0:
        return 1.0
    
    # Calculate scale factors for width and height
    width_scale = available_width / drawing_width
    height_scale = available_height / drawing_height
    
    # Use the smaller scale to maintain aspect ratio
    return min(width_scale, height_scale)

def distance(p, q):
    """Calculate Euclidean distance between two points."""
    return math.sqrt((p[0]-q[0])**2 + (p[1]-q[1])**2)

def chaikin_smooth(points, iterations):
    """Apply Chaikin's corner cutting algorithm to smooth the path."""
    if len(points) < 2:
        return points
    
    for _ in range(iterations):
        new_points = [points[0]]  # Keep the first point
        for i in range(len(points) - 1):
            p0 = points[i]
            p1 = points[i+1]
            # Calculate new points: 3/4 of p0 + 1/4 of p1, and 1/4 of p0 + 3/4 of p1
            q = (0.75 * p0[0] + 0.25 * p1[0], 0.75 * p0[1] + 0.25 * p1[1])
            r = (0.25 * p0[0] + 0.75 * p1[0], 0.25 * p0[1] + 0.75 * p1[1])
            new_points.append(q)
            new_points.append(r)
        new_points.append(points[-1])  # Keep the last point
        points = new_points

    return points

def write_gcode(points,
                filename,
                feedrate,
                z_feedrate,
                z_safe,
                z_draw):
    """
    Generate optimized G-code to move through the points.
    """
    with open(filename, 'w') as f:
        f.write("G21 ; Set units to mm\n")
        f.write("G90 ; Use absolute positioning\n")
        f.write(f"G1 Z{z_safe:.2f} F{z_feedrate:.2f} ; Move Z to safe height\n")

        # Move to the first point
        x0, y0 = points[0]
        f.write(f"G0 X{x0:.3f} Y{y0:.3f} ; Move to start position\n")
        f.write(f"G1 Z{z_draw:.2f} F{z_feedrate:.2f} ; Pen down\n")
        
        # Set XY feedrate once at the beginning of the path
        f.write(f"G1 F{feedrate:.0f} ; Set XY feedrate\n")
        
        # Draw the path (no need to repeat feedrate)
        for x, y in points[1:]:
            f.write(f"G1 X{x:.3f} Y{y:.3f}\n")

        # Pen up with fixed Z feedrate
        f.write(f"G1 Z{z_safe:.2f} F{z_feedrate:.2f} ; Pen up\n")
        f.write("M2 ; Program end\n")


def main(resolution,
         margin, 
         save_np_array,
         save_gcode,
         save_svg,
         size,
         feedrate,
         z_feedrate,
         z_safe,
         z_draw):
    
    # Parse command-line arguments
    if len(sys.argv) < 2:
        print("Usage: python script.py input_filename [N]")
        return
    
    input_filename = sys.argv[1]
    iterations = resolution  # Fixed iterations for Chaikin smoothing
    
    # create output directory if it doesn't exist
    if not os.path.exists("output"):
        os.makedirs("output")
    
    # Generate output base name
    base_name = os.path.splitext(os.path.basename(input_filename))
    base_name = base_name[0].split(".")[0] # Get the name without extension
    output_array = "output/" + base_name + "_smooth.npy"
    output_png = "output/" + base_name + "_smooth.png"
    output_svg = "output/" + base_name + "_smooth.svg"
    
    # Read input file - skip header lines
    points = []
    try:
        with open(input_filename, 'r') as f:
            # Skip the header lines
            for _ in range(2):
                next(f)
            
            # Process coordinate lines
            for line in f:
                line = line.strip()
                # Skip footer line if present
                if line.startswith('[') or not line:
                    continue
                parts = line.split()
                if len(parts) >= 2:
                    try:
                        x = float(parts[0])
                        y = float(parts[1])
                        points.append((x, y))
                    except ValueError:
                        continue
    except Exception as e:
        print(f"Error reading file: {e}")
        return
    
    if len(points) < 2:
        print("Not enough points to process.")
        return
    
    print(f"Read {len(points)} points from input file")
    
    # Calculate bounding box dimensions
    min_x = min(x for x, y in points)
    max_x = max(x for x, y in points)
    min_y = min(y for x, y in points)
    max_y = max(y for x, y in points)
    width = max_x - min_x
    height = max_y - min_y
    
    # Get scale factor for paper size
    scale = get_scale_factor(size, width, height, margin)
    print(f"Scale factor for paper size {size}: {scale}")
    
    # Process the points
    dense_points = chaikin_smooth(points, iterations)
    rotated_points = [(x, -y) for (x, y) in dense_points]
    move_y_axis = abs(min(y for x, y in rotated_points)) * scale  # Move Y-axis to positive values
    move_x_axis = abs(min(x for x, y in rotated_points)) * scale  # Move X-axis to positive values
    scaled_points = [((x * scale) + move_x_axis + margin, 
                      (y * scale) + move_y_axis + margin) for (x, y) in rotated_points]
    
    if save_np_array == 1:
        print("Saving numpy array...")
        # Convert to numpy array and save
        array_points = np.array(rotated_points)
        smooth_array = [array_points[:,0], array_points[:,1]]
        np.save(output_array, smooth_array)
        print(f"Saved numpy array to {output_array}")

    # Save G-code file with new parameters
    if save_gcode == 1:
        output_gcode = "output/" + base_name + "_smooth.gcode"
        print(f"Saving G-code to {output_gcode} with feedrate={feedrate}, with z_feedrate={z_feedrate}, z_safe={z_safe}, z_draw={z_draw}")
        write_gcode(scaled_points,
                    output_gcode,
                    feedrate,
                    z_feedrate,  # Use default Z-axis feedrate
                    z_safe,
                    z_draw)
    
    # Create plot
    plt.figure(figsize=(8, 8))
    x, y = zip(*rotated_points)
    plt.plot(x, y, 'k-', linewidth=0.2)  # Black stroke with 0.2 width
    plt.axis('equal')  # Maintain aspect ratio
    plt.axis('off')  # Remove axes
    
    # Save plot in both formats
    plt.savefig(output_png, dpi=600, bbox_inches='tight', pad_inches=0)
    if save_svg == 1:
        print(f"Saving SVG to {output_svg}")
        plt.savefig(output_svg, format='svg', bbox_inches='tight', pad_inches=0)
    plt.savefig(output_svg, bbox_inches='tight', pad_inches=0)
    print(f"Drawing Saved")

if __name__ == "__main__":
    args = parse_args()
    current_params = default_params.copy()
    current_params.update(vars(args))
    main(current_params['resolution'],
         current_params['paper_margin'], 
         current_params['save_np_array'],
         current_params['save_gcode'],
         current_params['save_svg'],
         current_params['paper_size'],
         current_params['feedrate'],  
         current_params['z_feedrate'],  
         current_params['z_safe'], 
         current_params['z_draw'])  
    
    print("Processing complete.")