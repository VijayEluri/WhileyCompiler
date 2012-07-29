/*
 * wycc_lib.h
 *
 * This is a a header file that describes the
 * library of support routines for programs written in
 * the Whiley language when translated into C (ala gcc)
 *
 * This file is part of the Whiley Development Kit (WDK).
 *
 * The Whiley Development Kit is free software; you can redistribute 
 * it and/or modify it under the terms of the GNU General Public 
 * License as published by the Free Software Foundation; either 
 * version 3 of the License, or (at your option) any later version.
 *
 * The Whiley Development Kit is distributed in the hope that it 
 * will be useful, but WITHOUT ANY WARRANTY; without even the 
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR 
 * PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public 
 * License along with the Whiley Development Kit. If not, see 
 * <http://www.gnu.org/licenses/>
 */

typedef struct Wycc_object {
    ;
    int typ;
    int cnt;
    void* ptr;
} wycc_obj;

/*
 * routines used by wycc for structure and bookkeeping
 */
void wycc_main();
// wycc_obj* wyil_obj_str(char* text);
wycc_obj* wycc_deref_box(wycc_obj* itm, int flg);
wycc_obj* wycc_box_str(char* text);

/*
 * routines to implement wyil operations
 */
void wyil_debug_str(char* mesg);
void wyil_debug_obj(wycc_obj *ptr);

/*
;;; Local Variables: ***
;;; c-basic-offset: 4 ***
;;; End: ***
 */
